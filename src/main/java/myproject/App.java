package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.rds.InstanceArgs;
import com.pulumi.aws.rds.*;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.route53.Record;

import java.util.List;
import java.util.Map;

import static com.pulumi.codegen.internal.Serialization.*;

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

        var databaseSecurityGroup = new SecurityGroup("dbSG", SecurityGroupArgs.builder()
                .description("Enable access to RDS Instance")
                .namePrefix("database-")
                .vpcId(main.id())
                .tags(Map.of("Name", "Database Security Group"))
                .build());

        var allowWebapp = new SecurityGroupRule("allowWebapp", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(3306)
                .toPort(3306)
                .protocol("tcp")
//                .cidrBlocks("0.0.0.0/0")
                .sourceSecurityGroupId(applicationSecurityGroup.id())
                .securityGroupId(databaseSecurityGroup.id())
                .build());

        var ec2Key = new KeyPair("ec2Key", KeyPairArgs.builder()
                .publicKey(config.require("pubKey"))
                .build());

        var dbParameterGroup = new ParameterGroup("db-pg", ParameterGroupArgs.builder()
                .family("mysql8.0")
                .build());

        numOfAz.applyValue(n -> {
            if (n >= 3) num[0] = 3;
            else num[0] = n;

            Subnet[] privateSubnet = new Subnet[num[0]];

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

                privateSubnet[i] = new Subnet("Private Subnet " + (i + 1), new SubnetArgs.Builder()
                        .vpcId(main.id())
                        .cidrBlock(cidrs[i + num[0]])
                        .availabilityZone(available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().get(finalIndex)))
                        .tags(Map.of("Name", "Private Subnet " + (i + 1)))
                        .build());

                var privateRouteTableAssociation = new RouteTableAssociation("PrivateRouteTableAssoc" + (i + 1), RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet[i].id())
                        .routeTableId(privateRouteTable.id())
                        .build());

                if (i == num[0] - 1) {
                    Output<List<String>> ids = null;
                    switch (num[0]) {
                        case 3: {
                            ids = Output.all(privateSubnet[0].id(), privateSubnet[1].id(), privateSubnet[2].id());
                            break;
                        }
                        case 2: {
                            ids = Output.all(privateSubnet[0].id(), privateSubnet[1].id());
                            break;
                        }
                        case 1: {
                            ids = Output.all(privateSubnet[0].id());
                            break;
                        }
                        default: {
                            System.exit(-1);
                        }
                    }
                    
                    var dbSubnetGroup = new SubnetGroup("db-sg", SubnetGroupArgs.builder()
                            .subnetIds(ids)
                            .tags(Map.of("Name", "My DB subnet group"))
                            .build());

                    var webappdb = new com.pulumi.aws.rds.Instance("webappdb", InstanceArgs.builder()
                            .engine("mysql")
                            .engineVersion("8.0.32")
                            .instanceClass("db.t3.micro")
                            .allocatedStorage(20)
                            .multiAz(false)
                            .dbName("webappdb")
                            .username("csye6225")
                            .password("PASSword123!")
                            .publiclyAccessible(false)
                            .vpcSecurityGroupIds(Output.all(databaseSecurityGroup.id()))
                            .dbSubnetGroupName(dbSubnetGroup.name())
                            .parameterGroupName(dbParameterGroup.name())
                            .skipFinalSnapshot(true)
                            .build());

                    var cloudwatchRole = new Role("cloudwatchRole", RoleArgs.builder()
                            .assumeRolePolicy(serializeJson(
                                    jsonObject(
                                            jsonProperty("Version", "2012-10-17"),
                                            jsonProperty("Statement", jsonArray(jsonObject(
                                                    jsonProperty("Action", "sts:AssumeRole"),
                                                    jsonProperty("Effect", "Allow"),
                                                    jsonProperty("Principal", jsonObject(
                                                            jsonProperty("Service", "ec2.amazonaws.com")
                                                    ))
                                            )))
                                    )))
                            .build());

                    var cwRoleAtta = new RolePolicyAttachment("cwRoleAtta", RolePolicyAttachmentArgs.builder()
                            .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
                            .role(cloudwatchRole.name())
                            .build());

                    var cwProfile = new InstanceProfile("cwProfile", InstanceProfileArgs.builder()
                            .role(cloudwatchRole.name())
                            .build());

                    var userData = Output.tuple(webappdb.username(), webappdb.password(), webappdb.endpoint(), webappdb.dbName())
                            .applyValue(t -> String.format(
                                    "#!/bin/bash\n" +
                                    "echo \"setup RDS endpoint\"\n" +
                                    "sed -i \"s|username=.*|username=%s|g\" /opt/csye6225/application.properties\n" +
                                    "sed -i \"s|password=.*|password=%s|g\" /opt/csye6225/application.properties\n" +
                                    "sed -i \"s|url=.*|url=jdbc:mysql://%s/%s?autoReconnect=true\\&useSSL=false\\&createDatabaseIfNotExist=true|g\" /opt/csye6225/application.properties\n" +
                                    "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \\\n" +
                                    "    -a fetch-config \\\n" +
                                    "    -m ec2 \\\n" +
                                    "    -c file:/opt/cloudwatch-config.json \\\n" +
                                    "    -s\n" +
                                    "sudo systemctl restart amazon-cloudwatch-agent.service" +
                                    "echo \"End of UserData\"\n", t.t1, t.t2.get(), t.t3, t.t4));

                    var webapp = new com.pulumi.aws.ec2.Instance("webapp", com.pulumi.aws.ec2.InstanceArgs.builder()
                            .ami(config.require("amiId"))
                            .associatePublicIpAddress(true)
                            .subnetId(publicSubnet.id())
                            .instanceType("t2.micro")
                            .vpcSecurityGroupIds(Output.all(applicationSecurityGroup.id()))
                            .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                                    .deleteOnTermination(true)
                                    .volumeSize(25)
                                    .volumeType("gp2")
                                    .build())
                            .keyName(ec2Key.keyName())
                            .userData(userData)
                            .iamInstanceProfile(cwProfile.name())
                            .tags(Map.of("Name", "webapp"))
                            .build());

                    var ARecord = new Record("A Record", RecordArgs.builder()
                            .zoneId(config.require("zoneId"))
                            .name(config.require("zoneName"))
                            .type("A")
                            .ttl(300)
                            .records(Output.all(webapp.publicIp()))
                            .build());
                }
            }

            return null;
        });

    }


}
