package myproject;

import org.apache.commons.net.util.SubnetUtils;

public class CIDRSubnetCalculator {
//    public static void main(String[] args) {
//        String cidr = "10.0.0.0/16";
//        int count = 6;
//        String cidrs[] = calculate(cidr, count);
//        for (String string: cidrs) {
//            System.out.println(string);
//        }
//    }

    public static String[] calculate(String cidr, int subnetCount) {
        String[] res = new String[subnetCount];
//        int num = (int) (Math.log(subnetCount) / Math.log(2)) + 1;


        SubnetUtils subnetUtils = new SubnetUtils(cidr);
        SubnetUtils.SubnetInfo info = subnetUtils.getInfo();

        // 获取原始 CIDR 地址范围
        String originalNetworkAddress = info.getNetworkAddress();
//        System.out.println(originalNetworkAddress);
        String originalBroadcastAddress = info.getBroadcastAddress();
//        System.out.println(originalBroadcastAddress);
        int subnetBitCount = 32 - Integer.parseInt(cidr.split("/")[1]);

        // 计算每个子网的地址范围
        for (int i = 0; i < subnetCount; i++) {
            int subnetSize = (int) Math.pow(2, subnetBitCount - 8);
            int step = subnetSize * (i + 1);

            Long subnetAddress = ip2Long(originalNetworkAddress) + step;
            String broadcastAddress = String.valueOf(ip2Long(originalNetworkAddress) + step + subnetSize - 1);

            String subnetCIDR = long2IP(subnetAddress) + "/" + (32 - subnetBitCount + 8);
            res[i] = subnetCIDR;
        }

        return res;
    }

    public static long ip2Long(String ip) {
        long ipNumber = 0;
        String[] ips = ip.split("[.]");
        for (int i = 0; i < 4; ++i) {
            ipNumber = ipNumber << 8 | Integer.parseInt(ips[i]);
        }
        return ipNumber;
    }

    public static String long2IP(long ipLong) {
        return ((ipLong >> 24) & 0xFF) + "." +
                ((ipLong >> 16) & 0xFF) + "." +
                ((ipLong >> 8) & 0xFF) + "." +
                (ipLong & 0xFF);
    }

}
