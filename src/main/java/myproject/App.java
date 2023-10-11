package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;

import java.util.Map;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context ctx) {
        var config = ctx.config("aws");
        String region = config.require("region");
        config = ctx.config();
        String cidr = config.require("vpcCidr");
//        String cidr = "10.0.0.0/16";
//        String[] cidrs = new String[] {"10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24", "10.0.4.0/24", "10.0.5.0/24"};
        String cidrs[] = CIDRSubnetCalculator.calculate(cidr, 6);
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

        for (int i = 1; i <= 3; i++) {
            var publicSubnet = new Subnet("Public Subnet " + i, new SubnetArgs.Builder()
                    .vpcId(main.id())
//                    .cidrBlock("10.0." + (i - 1) + ".0/24")
                    .cidrBlock(cidrs[i - 1])
//                    .availabilityZone("us-east-1" + (char) ('a' + i - 1))
                    .availabilityZone(region + (char) ('a' + i - 1))
                    .tags(Map.of("Name", "Public Subnet " + i))
                    .build());

            var publicRouteTableAssociation = new RouteTableAssociation("PublicRouteTableAssoc" + i, RouteTableAssociationArgs.builder()
                    .subnetId(publicSubnet.id())
                    .routeTableId(publicRouteTable.id())
                    .build());

            var privateSubnet = new Subnet("Private Subnet" + i, new SubnetArgs.Builder()
                    .vpcId(main.id())
//                    .cidrBlock("10.0." + (i + 2) + ".0/24")
                    .cidrBlock(cidrs[i + 2])
//                    .availabilityZone("us-east-1" + (char) ('a' + i + 2))
                    .availabilityZone(region + (char) ('a' + i - 1))
                    .tags(Map.of("Name", "Private Subnet " + i))
                    .build());

            var privateRouteTableAssociation = new RouteTableAssociation("PrivateRouteTableAssoc" + i, RouteTableAssociationArgs.builder()
                    .subnetId(privateSubnet.id())
                    .routeTableId(privateRouteTable.id())
                    .build());
        }

    }

}
