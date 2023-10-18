package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.core.Output;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context ctx) {
        int[] index = {0};
        int[] num = {3};
        final var available = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                .state("available")
                .build());

        Output<Integer> numOfAz = available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().size());

        var config = ctx.config();
        String cidr = config.require("vpcCidr");
        String cidrs[] = CIDRSubnetCalculator.calculate(cidr, num[0] * 2);

        var main = new Vpc("main", VpcArgs.builder()
                .cidrBlock(cidr)
                .tags(Map.of("Name", "myVpc"))
                .build());

        var igw = new InternetGateway("igw", InternetGatewayArgs.builder()
                .vpcId(main.id())
                .tags(Map.of("Name", "myIgw"))
                .build());

        var publicRouteTable = new RouteTable("Public Route Table", RouteTableArgs.builder()
                .vpcId(main.id())
                .routes(
                        RouteTableRouteArgs.builder()
                                .cidrBlock("0.0.0.0/0")
                                .gatewayId(igw.id())
                                .build()
                )
                .tags(Map.of("Name", "Public Route Table"))
                .build());

        var privateRouteTable = new RouteTable("Private Route Table", RouteTableArgs.builder()
                .vpcId(main.id())
                .tags(Map.of("Name", "Private Route Table"))
                .build());

        var applicationSecurityGroup = new SecurityGroup("appSG", SecurityGroupArgs.builder()
                .description("Allow inbound traffic")
                .namePrefix("application-")
                .vpcId(main.id())
                .ingress(SecurityGroupIngressArgs.builder()
                        .fromPort(8080)
                        .toPort(8080)
                        .cidrBlocks("0.0.0.0/0")
                        .protocol("tcp")
                        .build())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0)
                        .toPort(0)
                        .protocol("-1")
                        .cidrBlocks("0.0.0.0/0")
                        .build())
                .tags(Map.of("Name", "Application Security Group"))
                .build());

        var allowSSH = new SecurityGroupRule("allowSHH", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(22)
                .toPort(22)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
                .securityGroupId(applicationSecurityGroup.id())
                .build());

        var allowHttp = new SecurityGroupRule("allowHttp", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(80)
                .toPort(80)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
                .securityGroupId(applicationSecurityGroup.id())
                .build());

        var allowHttps = new SecurityGroupRule("allowHttps", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(443)
                .toPort(443)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
                .securityGroupId(applicationSecurityGroup.id())
                .build());

        Output<List<String>> sgIds = applicationSecurityGroup.id().applyValue(id -> {
            List<String> ids = new ArrayList<>();
            ids.add(id);
            return ids;
        });

        var ec2Key = new KeyPair("ec2Key", KeyPairArgs.builder()
                .publicKey(config.require("pubKey"))
                .build());

        numOfAz.applyValue(n -> {
            if (n >= 3) num[0] = 3;
            else num[0] = 2;

            for (int i = 0; i < num[0]; i++, index[0]++) {
                int finalIndex = index[0];
                var publicSubnet = new Subnet("Public Subnet " + (i + 1), new SubnetArgs.Builder()
                        .vpcId(main.id())
                        .cidrBlock(cidrs[i])
                        .availabilityZone(available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().get(finalIndex)))
                        .tags(Map.of("Name", "Public Subnet " + (i + 1)))
                        .build());

                var publicRouteTableAssociation = new RouteTableAssociation("PublicRouteTableAssoc" + i, RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnet.id())
                        .routeTableId(publicRouteTable.id())
                        .build());

                var privateSubnet = new Subnet("Private Subnet " + (i + 1), new SubnetArgs.Builder()
                        .vpcId(main.id())
                        .cidrBlock(cidrs[i + num[0]])
                        .availabilityZone(available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().get(finalIndex)))
                        .tags(Map.of("Name", "Private Subnet " + (i + 1)))
                        .build());

                var privateRouteTableAssociation = new RouteTableAssociation("PrivateRouteTableAssoc" + (i + 1), RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet.id())
                        .routeTableId(privateRouteTable.id())
                        .build());

                if (i == num[0] - 1) {
                    var webapp = new Instance("webapp", InstanceArgs.builder()
                            .ami(config.require("amiId"))
                            .associatePublicIpAddress(true)
                            .subnetId(publicSubnet.id())
                            .instanceType("t2.micro")
                            .vpcSecurityGroupIds(sgIds)
                            .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                                    .deleteOnTermination(true)
                                    .volumeSize(25)
                                    .volumeType("gp2")
                                    .build())
                            .keyName(ec2Key.keyName())
                            .tags(Map.of("Name", "webapp"))
                            .build());
                }
            }

            return null;
        });

    }


}
