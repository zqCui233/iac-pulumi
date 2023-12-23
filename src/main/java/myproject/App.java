package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.asset.FileAsset;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.Policy;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.autoscaling.inputs.GroupTagArgs;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.dynamodb.Table;
import com.pulumi.aws.dynamodb.TableArgs;
import com.pulumi.aws.dynamodb.inputs.TableAttributeArgs;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.kms.KeyArgs;
import com.pulumi.aws.lambda.*;
import com.pulumi.aws.lambda.inputs.FunctionEnvironmentArgs;
import com.pulumi.aws.lb.*;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.ec2.KeyPair;
import com.pulumi.aws.ec2.KeyPairArgs;
import com.pulumi.aws.lb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.aws.rds.InstanceArgs;
import com.pulumi.aws.rds.*;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.aws.s3.*;
import com.pulumi.aws.sns.Topic;
import com.pulumi.aws.sns.TopicArgs;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.route53.Record;
import com.pulumi.gcp.serviceAccount.IAMBinding;
import com.pulumi.gcp.serviceAccount.IAMBindingArgs;
import com.pulumi.gcp.storage.BucketIAMBinding;
import com.pulumi.gcp.storage.BucketIAMBindingArgs;
import com.pulumi.resources.StackReference;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pulumi.codegen.internal.Serialization.*;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context ctx) {
        var config = ctx.config();

        int[] index = {0};
        int[] num = {3};
        final var available = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                .state("available")
                .build());

//        var stackRef = new StackReference(config.require("stackName"));
//        var stackRef = new StackReference("organization/iac-pulumi-go/demo");
//        var mainID = stackRef.requireOutput(Output.of("main")).applyValue(String::valueOf);
//        var targetGroupArn = stackRef.requireOutput(Output.of("lbTargetGroup")).applyValue(String::valueOf);

        Output<Integer> numOfAz = available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().size());

        String cidr = config.require("vpcCidr");
        String cidrs[] = CIDRSubnetCalculator.calculate(cidr, num[0] * 2);

        var main = new Vpc("main", VpcArgs.builder()
                .cidrBlock(cidr)
                .tags(Map.of("Name", "myVpc"))
                .build());

        var igw = new InternetGateway("igw", InternetGatewayArgs.builder()
                .vpcId(main.id())
//                .vpcId(mainID)
                .tags(Map.of("Name", "myIgw"))
                .build());

        var publicRouteTable = new RouteTable("Public Route Table", RouteTableArgs.builder()
                .vpcId(main.id())
//                .vpcId(mainID)
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
//                .vpcId(mainID)
                .tags(Map.of("Name", "Private Route Table"))
                .build());

        var loadBalancerSecurityGroup = new SecurityGroup("lbSG", SecurityGroupArgs.builder()
                .description("SG for Load Balance")
                .namePrefix("loadBalance-")
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0)
                        .toPort(0)
                        .protocol("-1")
                        .cidrBlocks("0.0.0.0/0")
                        .build())
                .vpcId(main.id())
//                .vpcId(mainID)
                .tags(Map.of("Name", "Load Balance Security Group"))
                .build());

        var allowHttp = new SecurityGroupRule("allowHttp", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(80)
                .toPort(80)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
                .securityGroupId(loadBalancerSecurityGroup.id())
                .build());

        var allowHttps = new SecurityGroupRule("allowHttps", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(443)
                .toPort(443)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
                .securityGroupId(loadBalancerSecurityGroup.id())
                .build());

        var applicationSecurityGroup = new SecurityGroup("appSG", SecurityGroupArgs.builder()
                .description("Allow inbound traffic")
                .namePrefix("application-")
                .vpcId(main.id())
//                .vpcId(mainID)
//                .ingress(SecurityGroupIngressArgs.builder()
//                        .fromPort(8080)
//                        .toPort(8080)
//                        .cidrBlocks("0.0.0.0/0")
//                        .protocol("tcp")
//                        .build())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0)
                        .toPort(0)
                        .protocol("-1")
                        .cidrBlocks("0.0.0.0/0")
                        .build())
                .tags(Map.of("Name", "Application Security Group"))
                .build());

        var allowSSH = new SecurityGroupRule("allowSSH", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(22)
                .toPort(22)
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0")
//                .sourceSecurityGroupId(loadBalancerSecurityGroup.id())
                .securityGroupId(applicationSecurityGroup.id())
                .build());

        var allowApp = new SecurityGroupRule("allowApp", SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(8080)
                .toPort(8080)
                .protocol("tcp")
//                .cidrBlocks("0.0.0.0/0")
                .sourceSecurityGroupId(loadBalancerSecurityGroup.id())
                .securityGroupId(applicationSecurityGroup.id())
                .build());

//        var allowHttp = new SecurityGroupRule("allowHttp", SecurityGroupRuleArgs.builder()
//                .type("ingress")
//                .fromPort(80)
//                .toPort(80)
//                .protocol("tcp")
//                .cidrBlocks("0.0.0.0/0")
//                .securityGroupId(applicationSecurityGroup.id())
//                .build());
//
//        var allowHttps = new SecurityGroupRule("allowHttps", SecurityGroupRuleArgs.builder()
//                .type("ingress")
//                .fromPort(443)
//                .toPort(443)
//                .protocol("tcp")
//                .cidrBlocks("0.0.0.0/0")
//                .securityGroupId(applicationSecurityGroup.id())
//                .build());

        var databaseSecurityGroup = new SecurityGroup("dbSG", SecurityGroupArgs.builder()
                .description("Enable access to RDS Instance")
                .namePrefix("database-")
                .vpcId(main.id())
//                .vpcId(mainID)
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
            Subnet[] publicSubnet = new Subnet[num[0]];
            Subnet[] privateSubnet = new Subnet[num[0]];

            for (int i = 0; i < num[0]; i++, index[0]++) {
                int finalIndex = index[0];
                publicSubnet[i] = new Subnet("Public Subnet " + (i + 1), new SubnetArgs.Builder()
                        .vpcId(main.id())
//                        .vpcId(mainID)
                        .cidrBlock(cidrs[i])
                        .availabilityZone(available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().get(finalIndex)))
                        .tags(Map.of("Name", "Public Subnet " + (i + 1)))
                        .build());

                var publicRouteTableAssociation = new RouteTableAssociation("PublicRouteTableAssoc" + i, RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnet[i].id())
                        .routeTableId(publicRouteTable.id())
                        .build());

                privateSubnet[i] = new Subnet("Private Subnet " + (i + 1), new SubnetArgs.Builder()
                        .vpcId(main.id())
//                        .vpcId(mainID)
                        .cidrBlock(cidrs[i + num[0]])
                        .availabilityZone(available.applyValue(getAvailabilityZonesResult -> getAvailabilityZonesResult.names().get(finalIndex)))
                        .tags(Map.of("Name", "Private Subnet " + (i + 1)))
                        .build());

                var privateRouteTableAssociation = new RouteTableAssociation("PrivateRouteTableAssoc" + (i + 1), RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet[i].id())
                        .routeTableId(privateRouteTable.id())
                        .build());

                if (i == num[0] - 1) {
                    Output<List<String>> privateIds = null;
                    Output<List<String>> publicIds = null;
                    switch (num[0]) {
                        case 3: {
                            publicIds = Output.all(publicSubnet[0].id(), publicSubnet[1].id(), publicSubnet[2].id());
                            privateIds = Output.all(privateSubnet[0].id(), privateSubnet[1].id(), privateSubnet[2].id());
                            break;
                        }
                        case 2: {
                            publicIds = Output.all(publicSubnet[0].id(), publicSubnet[1].id());
                            privateIds = Output.all(privateSubnet[0].id(), privateSubnet[1].id());
                            break;
                        }
                        case 1: {
                            publicIds = Output.all(publicSubnet[0].id());
                            privateIds = Output.all(privateSubnet[0].id());
                            break;
                        }
                        default: {
                            System.out.println("ERROR MSG");
                            System.exit(-1);
                        }
                    }

                    var dbSubnetGroup = new SubnetGroup("db-sg", SubnetGroupArgs.builder()
                            .subnetIds(privateIds)
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

                    var instanceRole = new Role("instanceRole", RoleArgs.builder()
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
                            .role(instanceRole.name())
                            .build());

                    var snsPublish = new RolePolicyAttachment("SNSPublishPolicy",
                            RolePolicyAttachmentArgs.builder().role(instanceRole.name())
                                    .policyArn("arn:aws:iam::aws:policy/AmazonSNSFullAccess")
                                    .build());

                    var lambdaRolePolicy = new RolePolicyAttachment("AWSLambdaRolePolicy",
                            RolePolicyAttachmentArgs.builder().role(instanceRole.name())
                                    .policyArn("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
                                    .build());

                    var cwProfile = new InstanceProfile("cwProfile", InstanceProfileArgs.builder()
                            .role(instanceRole.name())
                            .build());

                    var topic = new Topic("myTopic", TopicArgs.builder()
                            .displayName("myTopic")
                            .build());

                    var gcpBucket = com.pulumi.gcp.storage.Bucket.get("existingBucket",
                            Output.of(config.require("bucket")), null, null);

                    var gcpAccount = new com.pulumi.gcp.serviceAccount.Account(
                            "myServiceAccount", com.pulumi.gcp.serviceAccount.AccountArgs.builder()
                            .project(config.require("project"))
                            .accountId(config.require("project"))
                            .build());

                    var gcpKey = new com.pulumi.gcp.serviceAccount.Key(
                            "myServiceAccountKey", com.pulumi.gcp.serviceAccount.KeyArgs.builder()
                            .serviceAccountId(gcpAccount.name())
                            .build());

                    var bucketAdminBinding = new BucketIAMBinding("bucketAdminBinding", BucketIAMBindingArgs.builder()
                            .bucket(gcpBucket.name()) // Replace with the actual bucket name or reference
                            .role("roles/storage.objectAdmin")
                            .members("allAuthenticatedUsers")
                            .build());

//                    var serviceAccountBinding = new IAMBinding("serviceAccountBinding", IAMBindingArgs.builder()
//                            .serviceAccountId(gcpAccount.name())
//                            .role("roles/storage.objectAdmin")
//                            .members("user:cuizhiqing110@gmail.com")
//                            .build());

                    List<String> permissions = new ArrayList<>();
                    permissions.add("cloudfunctions.functions.invoke");

                    var gcpRole = new com.pulumi.gcp.projects.IAMCustomRole("gcpRole",
                            com.pulumi.gcp.projects.IAMCustomRoleArgs.builder()
                                    .roleId("myapplambdarole")
                                    .project(config.require("project"))
                                    .title("LambdaRole")
                                    .description("IAM role for lambda Function")
                                    .permissions(Output.of(permissions))
                                    .build());

                    var dynamoDBTable = new Table("myDynamoDBTable", TableArgs.builder()
                            .name("DynamoTable")
                            .attributes(TableAttributeArgs.builder()
                                    .name("ID")
                                    .type("S")
                                    .build())
                            .hashKey("ID")
                            .billingMode("PAY_PER_REQUEST")
                            .build());

                    var lambdaRole = new Role("lambdaRole", RoleArgs.builder()
                            .assumeRolePolicy(
                                    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Action\":\"sts:AssumeRole\",\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Effect\":\"Allow\"}]}")
                            .build());

                    var policyAttachment = new RolePolicyAttachment("policyAttachment", RolePolicyAttachmentArgs.builder()
                            .role(lambdaRole.name())
                            .policyArn("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
                            .build());

                    var snsPublish1 = new RolePolicyAttachment("SNSPublishPolicyLambda",
                            RolePolicyAttachmentArgs.builder().role(lambdaRole.name())
                                    .policyArn("arn:aws:iam::aws:policy/AmazonSNSFullAccess")
                                    .build());

                    var cloudWatchPolicyLambda = new RolePolicyAttachment("CloudWatchPolicyLambda",
                            RolePolicyAttachmentArgs.builder().role(lambdaRole.name())
                                    .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
                                    .build());

                    var dynamoDBPolicy = new RolePolicyAttachment("dynamoDBPolicy",
                            RolePolicyAttachmentArgs.builder().role(lambdaRole.name())
                                    .policyArn("arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess")
                                    .build());

//                    var lambdaFunction = new com.pulumi.gcp.cloudfunctions.Function("lambdaFunction", com.pulumi.gcp.cloudfunctions.FunctionArgs.builder()
//                            .project(config.require("project"))
//                            .region("us-east1")
//                            .runtime("java17")
//                            .httpsTriggerUrl("https://github.com/zqCui233/serverless.git")
//                            .sourceArchiveBucket(gcpBucket.name())
//                            .sourceArchiveObject("/Users/cuizhiqing/Desktop/serverless.zip")
//                            .entryPoint("Lambda.java")
//                            .triggerHttp(true)
//                            .build());

                    Output<String> dynamoDBTableOutput = dynamoDBTable.name().applyValue(ids -> ids);
                    Output<String> KeyOutput = gcpKey.privateKey().applyValue(ids -> ids);
                    Output<String> snsTopicOutput = topic.arn().applyValue(ids -> ids);

                    Output<Map<String, String>> environment = KeyOutput.apply(skey -> dynamoDBTableOutput
                            .apply(dynamodb -> snsTopicOutput.applyValue(topicArn -> {
                                Map<String, String> e = new HashMap<>();
                                e.put("GOOGLE_ACCESS_KEY", skey);
                                e.put("BUCKET_NAME", config.require("bucket"));
                                e.put("SNS_TOPIC_ARN", topicArn);
                                e.put("MAILGUN_APIKEY", "71c34845893e6d38466738d23112d328-0a688b4a-94d0707f");
                                e.put("DOMAIN", "cuizhiqing.me");
                                e.put("dynamoTable", dynamodb);

                                return e;
                            })));

//                    var fileArchive = new com.pulumi.asset.FileArchive("/Users/cuizhiqing/Desktop/serverless.zip");
                    var fileArchive = new com.pulumi.asset.FileArchive("/Users/cuizhiqing/Desktop/serverless/target/lambda-1.0-SNAPSHOT.jar");

                    var bucket = new Bucket("bucket", BucketArgs.builder()
                            .acl("private")
                            .build());

                    var bucketObject = new BucketObject("object", BucketObjectArgs.builder()
                            .bucket(bucket.id())
                            .source(fileArchive)
                            .build());

                    var lambdaFunction1 = new Function("LambdaFunction1", FunctionArgs.builder()
                            .runtime("java17")
                            .packageType("Zip")
                            .role(lambdaRole.arn())
                            .s3Bucket(bucket.id())
                            .s3Key(bucketObject.key())
                            .handler("com.neu.csye6225.Lambda::handleRequest")
                            .environment(FunctionEnvironmentArgs.builder()
                                    .variables(environment)
                                    .build())
                            .timeout(60)
                            .build());

                    var snsSubscription = new TopicSubscription("snsSubscription", TopicSubscriptionArgs.builder()
                            .endpoint(lambdaFunction1.arn())
                            .protocol("lambda")
                            .topic(topic.arn())
                            .build());

                    var lambdaPermission = new Permission("lambdaPermission",
                            PermissionArgs.builder()
                                    .action("lambda:InvokeFunction")
                                    .function(lambdaFunction1.arn())
                                    .principal("sns.amazonaws.com")
                                    .sourceArn(topic.arn())
                                    .build());

                    var userData = Output.tuple(topic.arn(), webappdb.username(), webappdb.password(), webappdb.endpoint(), webappdb.dbName())
                            .applyValue(t -> String.format(
                                    "#!/bin/bash\n" +
                                            "echo \"setup RDS endpoint\"\n" +
                                            "sed -i \"s|aws.topic.arn=.*|aws.topic.arn=%s|g\" /opt/csye6225/application.properties\n" +
                                            "sed -i \"s|username=.*|username=%s|g\" /opt/csye6225/application.properties\n" +
                                            "sed -i \"s|password=.*|password=%s|g\" /opt/csye6225/application.properties\n" +
                                            "sed -i \"s|url=.*|url=jdbc:mysql://%s/%s?autoReconnect=true\\&useSSL=false\\&createDatabaseIfNotExist=true|g\" /opt/csye6225/application.properties\n" +
                                            "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \\\n" +
                                            "    -a fetch-config \\\n" +
                                            "    -m ec2 \\\n" +
                                            "    -c file:/opt/cloudwatch-config.json \\\n" +
                                            "    -s\n" +
                                            "sudo systemctl restart amazon-cloudwatch-agent.service" +
                                            "echo \"End of UserData\"\n", t.t1, t.t2, t.t3.get(), t.t4, t.t5));

//                    var webapp = new com.pulumi.aws.ec2.Instance("webapp", com.pulumi.aws.ec2.InstanceArgs.builder()
//                            .ami(config.require("amiId"))
//                            .associatePublicIpAddress(true)
//                            .subnetId(publicSubnet.id())
//                            .instanceType("t2.micro")
//                            .vpcSecurityGroupIds(Output.all(applicationSecurityGroup.id()))
//                            .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
//                                    .deleteOnTermination(true)
//                                    .volumeSize(25)
//                                    .volumeType("gp2")
//                                    .build())
//                            .keyName(ec2Key.keyName())
//                            .userData(userData)
//                            .iamInstanceProfile(cwProfile.name())
//                            .tags(Map.of("Name", "webapp"))
//                            .build());

                    var asConfig = new LaunchConfiguration("asConf", LaunchConfigurationArgs.builder()
                            .imageId(config.require("amiId"))
                            .associatePublicIpAddress(true)
                            .instanceType("t2.micro")
                            .keyName(ec2Key.keyName())
                            .userData(userData)
                            .iamInstanceProfile(cwProfile.name())
                            .securityGroups(Output.all(applicationSecurityGroup.id()))
                            .rootBlockDevice(LaunchConfigurationRootBlockDeviceArgs.builder()
                                    .deleteOnTermination(true)
                                    .encrypted(true)
                                    .volumeSize(25)
                                    .volumeType("gp2")
                                    .build())
                            .build());

                    var targetGroup = new TargetGroup("lbTargetGroup", TargetGroupArgs.builder()
                            .targetType("instance")
                            .name("lbTargetGroup")
                            .port(8080)
                            .protocol("HTTP")
                            .vpcId(main.id())
                            .healthCheck(TargetGroupHealthCheckArgs.builder()
                                    .port("8080")
                                    .protocol("HTTP")
                                    .path("/healthz")
                                    .matcher("200")
                                    .build())
                            .build());

                    var autoScalingGroup = new com.pulumi.aws.autoscaling.Group("asGroup", GroupArgs.builder()
                            .defaultCooldown(60)
                            .launchConfiguration(asConfig.name())
                            .maxSize(3)
                            .minSize(1)
                            .desiredCapacity(1)
                            .targetGroupArns(Output.all(targetGroup.arn()))
//                            .targetGroupArns(Output.all(targetGroupArn))
                            .vpcZoneIdentifiers(publicIds)
                            .tags(GroupTagArgs.builder()
                                    .key("Name")
                                    .propagateAtLaunch(true)
                                    .value("webapp")
                                    .build())
                            .build());

                    var asPolicyUp = new Policy("Scale up", PolicyArgs.builder()
                            .scalingAdjustment(1)
                            .adjustmentType("ChangeInCapacity")
                            .autoscalingGroupName(autoScalingGroup.name())
                            .build());

                    var asPolicyDown = new Policy("Scale down", PolicyArgs.builder()
                            .scalingAdjustment(-1)
                            .adjustmentType("ChangeInCapacity")
                            .autoscalingGroupName(autoScalingGroup.name())
                            .build());

                    var CPUHigh = new MetricAlarm("High CPU Usage", MetricAlarmArgs.builder()
                            .comparisonOperator("GreaterThanThreshold")
                            .metricName("CPUUtilization")
                            .evaluationPeriods(2)
                            .namespace("AWS/EC2")
                            .statistic("Average")
                            .threshold(5.0)
                            .period(60)
                            .alarmActions(Output.all(asPolicyUp.arn()))
                            .build());

                    var CPULow = new MetricAlarm("Low CPU Usage", MetricAlarmArgs.builder()
                            .comparisonOperator("LessThanThreshold")
                            .metricName("CPUUtilization")
                            .evaluationPeriods(2)
                            .namespace("AWS/EC2")
                            .statistic("Average")
                            .threshold(3.0)
                            .period(60)
                            .alarmActions(Output.all(asPolicyDown.arn()))
                            .build());

                    var loadBalancer = new LoadBalancer("Load Balancer", LoadBalancerArgs.builder()
                            .name("application-Load-Balancer")
                            .loadBalancerType("application")
                            .securityGroups(Output.all(loadBalancerSecurityGroup.id()))
                            .subnets(publicIds)
                            .ipAddressType("ipv4")
                            .build());

                    var listener = new Listener("Listener", ListenerArgs.builder()
                            .loadBalancerArn(loadBalancer.arn())
                            .port(80)
                            .protocol("HTTP")
                            .defaultActions(ListenerDefaultActionArgs.builder()
                                    .type("forward")
//                                    .targetGroupArn(targetGroupArn)
                                    .targetGroupArn(targetGroup.arn())
                                    .build())
                            .build());

                    var ARecord = new Record("A Record", RecordArgs.builder()
                            .zoneId(config.require("zoneId"))
                            .name(config.require("zoneName"))
                            .type("A")
                            .aliases(RecordAliasArgs.builder()
                                    .name(loadBalancer.dnsName())
                                    .zoneId(loadBalancer.zoneId())
                                    .evaluateTargetHealth(true)
                                    .build())
//                            .ttl(300)
//                            .records(Output.all(webapp.publicIp()))
                            .build());
                }
            }

            return null;
        });

    }


}
