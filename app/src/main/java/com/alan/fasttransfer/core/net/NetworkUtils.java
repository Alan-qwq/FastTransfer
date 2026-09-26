package com.alan.fasttransfer.core.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * 网络相关的小工具。
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    /** 取本机在局域网中的 IPv4 地址，取不到时返回 null。 */
    public static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            String fallback = null;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (ip == null) {
                        continue;
                    }
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172.") || ip.startsWith("169.254.")) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取值是否像点分十进制 IPv4。 */
    public static boolean isIpv4(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > 15) {
            return false;
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int value2;
            try {
                value2 = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (value2 < 0 || value2 > 255) {
                return false;
            }
        }
        return true;
    }

    /** 把 "192.168.1.5:53317" 拆成地址与端口；端口缺省时返回 defaultPort。 */
    public static String[] splitHostPort(String input, int defaultPort) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return null;
        }
        String host = text;
        int port = defaultPort;
        int colon = text.lastIndexOf(':');
        if (colon > 0 && text.indexOf(':') == colon) {
            host = text.substring(0, colon).trim();
            String portText = text.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!isIpv4(host) || port < 1 || port > 65535) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }

    /** 在网段内做一次快速探测时可用的前缀。 */
    public static String subnetPrefix(String ip) {
        if (!isIpv4(ip)) {
            return null;
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot + 1) : null;
    }
}
