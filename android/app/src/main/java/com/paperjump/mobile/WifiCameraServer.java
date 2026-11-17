package com.paperjump.mobile;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class WifiCameraServer {
    private static final int PORT = 8767;
    private static final int DISCOVERY_PORT = 8766;
    private final byte[] key;
    private final byte[] session = new byte[16];
    private final SecureRandom random = new SecureRandom();
    private final Object frameLock = new Object();
    private volatile boolean running = true;
    private volatile boolean connected;
    private byte[] frame;
    private long frameNumber;
    private ServerSocket server;

    WifiCameraServer(String keyBase64) throws Exception {
        key = Base64.decode(keyBase64, Base64.DEFAULT);
        if (key.length != 32) throw new IllegalArgumentException("Invalid pairing key");
        random.nextBytes(session);
        server = new ServerSocket(PORT);
        new Thread(this::announce, "paperjump-wifi-discovery").start();
        new Thread(this::accept, "paperjump-wifi-camera").start();
    }

    boolean isConnected() { return connected; }

    void offerFrame(byte[] jpeg) {
        synchronized (frameLock) {
            frame = jpeg;
            frameNumber++;
            frameLock.notifyAll();
        }
    }

    void stop() {
        running = false;
        connected = false;
        try { server.close(); } catch (Exception ignored) { }
        synchronized (frameLock) { frameLock.notifyAll(); }
    }

    private byte[] sign(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private void announce() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            String nonce = hex(session);
            String message = "paperjump-wifi-v1|" + nonce;
            byte[] packet = (message + "|" + hex(sign(message))).getBytes(StandardCharsets.US_ASCII);
            while (running) {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                boolean sent = false;
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface network = interfaces.nextElement();
                    try {
                        if (!network.isUp() || network.isLoopback()) continue;
                        for (InterfaceAddress address : network.getInterfaceAddresses()) {
                            InetAddress broadcast = address.getBroadcast();
                            if (broadcast == null) continue;
                            socket.send(new DatagramPacket(packet, packet.length,
                                    broadcast, DISCOVERY_PORT));
                            sent = true;
                        }
                    } catch (Exception ignored) { }
                }
                if (!sent) socket.send(new DatagramPacket(packet, packet.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                Thread.sleep(1000);
            }
        } catch (Exception ignored) { }
    }

    private void accept() {
        while (running) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(15000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine();
                String expected = "GET /stream?token=" + hex(sign("stream|" + hex(session))) + " HTTP/1.1";
                if (!expected.equals(request)) continue;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                OutputStream output = client.getOutputStream();
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                connected = true;
                long sent = -1;
                while (running && !client.isClosed()) {
                    byte[] next;
                    synchronized (frameLock) {
                        while (running && sent == frameNumber) frameLock.wait(1000);
                        if (!running) break;
                        next = frame;
                        sent = frameNumber;
                    }
                    if (next == null) continue;
                    byte[] iv = new byte[12];
                    random.nextBytes(iv);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, iv));
                    byte[] encrypted = cipher.doFinal(next);
                    output.write(ByteBuffer.allocate(4).putInt(iv.length + encrypted.length).array());
                    output.write(iv);
                    output.write(encrypted);
                    output.flush();
                }
            } catch (Exception ignored) {
            } finally {
                connected = false;
            }
        }
    }
}
