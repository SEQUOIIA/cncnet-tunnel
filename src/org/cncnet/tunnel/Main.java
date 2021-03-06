/*
 * Copyright (c) 2013 Toni Spets <toni.spets@iki.fi>
 * 
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.cncnet.tunnel;

import com.sun.net.httpserver.HttpServer;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Date;
import java.util.Iterator;
import javax.swing.JFrame;
import javax.swing.UIManager;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class Main {

    static FileOutputStream logStream = null;
    static StatusWindow statusWindow = null;
    static TunnelController controller = null;

    // -name <str>          Custom name for the tunnel
    // -maxclients <num>    Maximum number of ports to allocate
    // -password <num>      Usage password
    // -port <num>          The port games are routed at
    // -masterpw <str>      Optional password to send to master when registering
    // -nomaster            Don't register to master
    // -logfile <str>       Log everything to this file
    // -headless            Don't start up the GUI
    // -iplimit             Enable (currently too strict) hosting rate limit
    // -maintpw <str>       Enable maintenance mode with password

    protected static String name = "Unnamed CnCNet 5a tunnel";
    protected static int maxclients = 8;
    protected static String password = null;
    protected static int port = 50000;
    protected static String bindaddress = "0.0.0.0";
    protected static String master = "http://cncnet.org/master-announce";
    protected static String masterpw = null;
    protected static boolean nomaster = false;
    protected static boolean headless = false;
    protected static int iplimit = 2;
    protected static String logfile = null;
    protected static String maintpw = null;

    public static void main(String[] args) {

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-name") && i < args.length - 1) {
                name = args[++i];
            } else if (args[i].equals("-maxclients") && i < args.length - 1) {
                maxclients = Math.max(Math.abs(Integer.parseInt(args[++i])), 2);
            } else if (args[i].equals("-password") && i < args.length - 1) {
                password = args[++i];
            } else if (args[i].equals("-port") && i < args.length - 1) {
                port = Math.max(Math.abs(Integer.parseInt(args[++i])), 1024);
            } else if (args[i].equals("-bindaddress") && i < args.length - 1) {
                bindaddress = args[++i];
            } else if (args[i].equals("-master") && i < args.length - 1) {
                master = args[++i];
            } else if (args[i].equals("-masterpw") && i < args.length - 1) {
                masterpw = args[++i];
            } else if (args[i].equals("-nomaster")) {
                nomaster = true;
            } else if (args[i].equals("-logfile") && i < args.length - 1) {
                logfile = args[++i];
            } else if (args[i].equals("-headless")) {
                headless = true;
            } else if (args[i].equals("-iplimit") && i < args.length - 1) {
                iplimit = Math.abs(Integer.parseInt(args[++i]));
            } else if (args[i].equals("-maintpw") && i < args.length - 1) {
                maintpw = args[++i];
            } else if (args[i].equals("-help") || args[i].equals("-h") || args[i].equals("-?") || args[i].equals("/h") || args[i].equals("/?")) {
                System.out.println("Arguments: [-name <string>] [-maxclients <number>] [-password <string>] [-port <number>] [-bindaddress <IP>] [-master <URL>] [-masterpw <string>] [-nomaster] [-logfile <path>] [-iplimit <number>] [-maintpw <string>]");
                return;
            } else {
                Main.log("Unknown parameter: " + args[i]);
            }
        }

        if (!headless) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) { }

            ConfigurationWindow configurationWindow = new ConfigurationWindow();
            configurationWindow.setVisible(true);
            configurationWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        } else {
            start();
        }
    }

    public static void start() {

        if (!headless) {
            statusWindow = new StatusWindow();
            statusWindow.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            statusWindow.status("Initializing...");
            statusWindow.setVisible(true);
        }

        port = Math.min(Math.max(port, 1024), 65535);

        if (logfile != null) {
            try {
                logStream = new FileOutputStream(logfile, true);
            } catch (IOException e) {
                // silently ignore errors
            }
        }

        Main.log("CnCNet tunnel starting...");
        Main.log("Name       : " + name);
        Main.log("Max clients: " + maxclients);
        if (password != null)
            Main.log("Password   : " + password);
        Main.log("Port       : " + port);
        if (masterpw != null && !nomaster)
            Main.log("Master pass: " + masterpw);
        if (nomaster)
            Main.log("Master server disabled.");
        else
            Main.log("Master     : " + master);

        if (maintpw != null)
            Main.log("Maintenance: " + maintpw);

        if (logStream != null) {
            Main.log("Logging to " + logfile);
        }

        Main.log("Host rate limit is " + (iplimit > 0 ? iplimit + " games per ip" : "disabled") + ".");

        try {
            Selector selector = Selector.open();
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.socket().bind(new InetSocketAddress(bindaddress, port));
            channel.register(selector, SelectionKey.OP_READ);

            controller = new TunnelController(name, password, port, maxclients, nomaster ? null : master, masterpw, iplimit);

            // setup our HTTP server
            HttpServer server = HttpServer.create(new InetSocketAddress(bindaddress, port), 4);
            server.createContext("/request", controller);
            server.createContext("/status", controller);
            if (maintpw != null) {
                server.createContext("/maintenance/" + maintpw, controller);
            }
            server.setExecutor(null);
            server.start();

            new Thread(controller).start();

            ByteBuffer buf = ByteBuffer.allocate(4096);

            while (true) {
                if (selector.select() > 0) {

                    long now = System.currentTimeMillis();

                    for (Iterator<SelectionKey> i = selector.selectedKeys().iterator(); i.hasNext();) {
                        SelectionKey k = i.next();
                        DatagramChannel chan = (DatagramChannel)k.channel();

                        try {
                            buf.clear();
                            InetSocketAddress from = (InetSocketAddress)chan.receive(buf);
                            buf.flip();

                            short hdrFrom = buf.getShort();
                            short hdrTo = buf.getShort();

                            buf.rewind();

                            Client clientFrom = controller.getClient(hdrFrom);
                            Client clientTo = controller.getClient(hdrTo);

                            if (clientFrom != null) {
                                if (clientFrom.getAddress() == null) {
                                    clientFrom.setAddress(from);
                                } else {
                                    // don't allow faking client id
                                    if (!from.getAddress().equals(clientFrom.getAddress().getAddress()))
                                        clientFrom = null;
                                }
                            }

                            if (clientFrom == null || clientTo == null || hdrFrom == hdrTo || !clientTo.isKnownClient(clientFrom.getId())) {
                                Main.log("Ignoring packet from " + hdrFrom + " to " + hdrTo + " (" + from + "), was " + buf.limit() + " bytes");
                            } else {
                                clientFrom.setLastPacket(now);

                                if (clientTo.getAddress() != null) {
                                    chan.send(buf, clientTo.getAddress());
                                }
                            }
                        } catch (IOException e) {
                            Main.log("IOException when handling event: " + e.getMessage());
                        } catch (BufferUnderflowException e) {
                            Main.log("BufferUnderflowException when handling event: " + e.getMessage());
                        } catch (BufferOverflowException e) {
                            Main.log("BufferOverflowException when handling event: " + e.getMessage());
                        }

                        if (!k.channel().isOpen()) {
                            k.cancel();
                        }

                        i.remove();
                    }
                }
            }

        } catch (Exception e) {
            Main.log(e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void log(String s) {
        for (String line : s.split("\n")) {
            String out = "[" + new Date().toString() + "] " + line;
            System.out.println(out);

            if (statusWindow != null) {
                statusWindow.log(out);
            }

            if (logStream != null) {
                out += "\n";
                try {
                    logStream.write(out.getBytes());
                } catch (IOException e) {

                }
            }
        }
    }

    public static void status(String s) {
        if (statusWindow != null) {
            statusWindow.status(s);
        }
    }
}
