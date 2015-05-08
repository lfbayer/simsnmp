package com.lbayer.simsnmp;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.concurrent.Semaphore;

public class Server implements CommandResponder
{
    private static final Logger LOGGER = Logger.getLogger(Server.class);

    private Snmp snmp;
    private int port;
    private String handler;
    private long lastModified;
    private Invocable handlerImpl;

    protected Server()
    {
    }

    public static void main(String[] args)
    {
        String config = "agent.conf";
        ListIterator<String> listIterator = Arrays.asList(args).listIterator();
        while (listIterator.hasNext())
        {
            String next = listIterator.next();
            if (next.equals("-c"))
            {
                if (!listIterator.hasNext())
                {
                    System.err.println("Invalid arguments: No configuration specified");
                    System.exit(1);
                    return;
                }

                config = listIterator.next();
                if (!new File(config).isFile())
                {
                    System.err.println("Invalid arguments: No such file: " + config);
                    System.exit(2);
                    return;
                }
            }
        }

        Properties props = new Properties();

        File file = new File(config);
        if (file.isFile())
        {
            try (FileReader reader = new FileReader(file))
            {
                props.load(reader);
            }
            catch (IOException e)
            {
                System.err.println("Configuration error: Error loading file: " + config);
                System.err.println(e.getMessage());
                System.exit(3);
                return;
            }
        }

        Logger rootLogger = Logger.getRootLogger();

        ConsoleAppender consoleAppender = new ConsoleAppender(new PatternLayout("%d{HH:mm:ss,SSS} [%-25t] %-5p - %m%n"));
        rootLogger.addAppender(consoleAppender);
        consoleAppender.setTarget("System.err");
        consoleAppender.setThreshold(Level.INFO);
        consoleAppender.activateOptions();

        try
        {
            Server server = new Server();
            server.port = Integer.parseInt(props.getProperty("port", "161"));
            server.handler = props.getProperty("handler", "agent.js");
            server.startServer();
        }
        catch (Throwable e)
        {
            LOGGER.error("Error running server", e);
        }
    }

    private synchronized Invocable getHandler() throws ScriptException, IOException
    {
        long newLastModified = new File(handler).lastModified();

        if (handlerImpl == null || newLastModified != lastModified)
        {
            lastModified = newLastModified;

            if (handlerImpl != null)
            {
                LOGGER.info("Handler script modified, reloading");
            }

            try (Reader r = new FileReader(handler))
            {
                ScriptContext ctx = new SimpleScriptContext();
                Bindings bindings = ctx.getBindings(ScriptContext.ENGINE_SCOPE);
                bindings.put("LOGGER", LOGGER);

                ScriptEngineManager engineManager = new ScriptEngineManager();
                ScriptEngine engine = engineManager.getEngineByName("nashorn");
                engine.setContext(ctx);
                engine.eval(r);

                handlerImpl = (Invocable) engine;
            }
        }

        return handlerImpl;
    }

    public VariableBinding getValue(String source, String target, int type, OID oid)
    {
        try
        {
            String method;
            switch (type)
            {
            case PDU.GET:
                method = "get";
                break;

            case PDU.GETNEXT:
                method = "getnext";
                break;

            default:
                return null;
            }

            return (VariableBinding) getHandler().invokeFunction(method, source, target, oid.toDottedString());
        }
        catch (NoSuchMethodException | ScriptException | IOException e)
        {
            LOGGER.error("Error executing handler script", e);
            return null;
        }
    }

    @Override
    public void processPdu(CommandResponderEvent event)
    {
        try
        {
            PDU reqPdu = event.getPDU();
            handleGet(event, reqPdu);
        }
        catch (Throwable e)
        {
            LOGGER.error("Error handling request", e);
        }
    }

    private void handleGet(CommandResponderEvent event, PDU reqPdu)
    {
        PDU pdu = (PDU) event.getPDU().clone();
        pdu.setType(PDU.RESPONSE);

        List<VariableBinding> newVariables = new ArrayList<>();

        String source = ((IpAddress) event.getPeerAddress()).getInetAddress().getHostAddress();
        String target = ((NettyUdpTransportMapping) event.getTransportMapping()).getLocalIp();
        for (VariableBinding reqVar : reqPdu.getVariableBindings())
        {
            OID oid = reqVar.getOid();

            VariableBinding binding = getValue(source, target, reqPdu.getType(), oid);
            if (binding == null)
            {
                pdu.setErrorStatus(SnmpConstants.SNMP_ERROR_NO_SUCH_NAME);
                if (event.getMessageProcessingModel() == MessageProcessingModel.MPv1)
                {
                    pdu.setErrorIndex(PDU.noSuchName);
                }
                else
                {
                    pdu.setErrorIndex(0);
                    reqVar.setVariable(Null.noSuchObject);
                }
            }
            else
            {
                reqVar = binding;
            }

            newVariables.add(reqVar);
        }

        pdu.setVariableBindings(newVariables);

        try
        {
            snmp.getMessageDispatcher().returnResponsePdu(
                    event.getMessageProcessingModel(),
                    event.getSecurityModel(),
                    event.getSecurityName(),
                    event.getSecurityLevel(),
                    pdu,
                    event.getMaxSizeResponsePDU(),
                    event.getStateReference(),
                    new StatusInformation());
        }
        catch (MessageException e)
        {
            e.printStackTrace();
        }
    }

    private List<String> getBindAddresses() throws SocketException
    {
        List<String> addresses = new ArrayList<>();

        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        if (ifaces != null)
        {
            while (ifaces.hasMoreElements())
            {
                Enumeration<InetAddress> inetAddresses = ifaces.nextElement().getInetAddresses();
                while (inetAddresses.hasMoreElements())
                {
                    String addr = inetAddresses.nextElement().getHostAddress();
                    addresses.add(addr);
                    LOGGER.debug("Added listen address: " + addr);
                }
            }
        }

        return addresses;
    }

    public void startServer() throws IOException, InterruptedException
    {
        LOGGER.info("Starting SNMP server");

        List<String> addresses = getBindAddresses();
        if (addresses.isEmpty())
        {
            LOGGER.warn("No addresses to bind to");
            return;
        }

        NettyUdpTransportMapping transport = new NettyUdpTransportMapping(addresses, port);

        snmp = new Snmp(transport);
        snmp.addCommandResponder(this);
        USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
        SecurityModels.getInstance().addSecurityModel(usm);
        snmp.listen();

        LOGGER.info("Listening for SNMP requests");

        Semaphore semaphore = new Semaphore(0);
        semaphore.acquireUninterruptibly();
    }
}
