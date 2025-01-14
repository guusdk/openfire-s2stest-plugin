package org.igniterealtime.openfire.s2sconformancetest;

/*

This entire file is almost entirely copied from the S2STestService in Openfire, from the following location:
/xmppserver/src/main/java/org/jivesoftware/util/S2STestService.java

 */

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.handler.IQPingHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.server.RemoteServerManager;
import org.jivesoftware.openfire.session.*;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.cert.SANCertificateIdentityMapping;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.IQ.Type;
import org.xmpp.packet.Packet;

import javax.xml.bind.DatatypeConverter;
import java.io.StringWriter;
import java.io.Writer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Runs server to server test.
 *
 * Attempts to send an IQ packet to ping a given domain. Captures debug information from logging, certificates and
 * packets.
 */
public class S2STestService {

    private static final org.slf4j.Logger Log = LoggerFactory.getLogger(S2STestService.class);

    private Semaphore waitUntil;

    private String domain;

    /**
     * @param domain The host to test.
     */
    public S2STestService(String domain) {
        this.domain = domain;
    }

    /**
     * Run a test against the domain.
     * @return K-V pairs of debug information.
     * @throws Exception On error.
     */
    public Map<String, String> run() throws Exception {
        waitUntil = new Semaphore(0);
        Map<String, String> results = new HashMap<>();
        final DomainPair pair = new DomainPair(XMPPServer.getInstance().getServerInfo().getXMPPDomain(), domain);

        // Tear down existing routes.
        final SessionManager sessionManager = SessionManager.getInstance();
        for (final Session incomingServerSession : sessionManager.getIncomingServerSessions( domain ) )
        {
            incomingServerSession.close();
        }

        // TODO: This shouldn't be changed to domain, to get the full list for this host?
        final Session outgoingServerSession = sessionManager.getOutgoingServerSession( pair );
        if ( outgoingServerSession != null )
        {
            outgoingServerSession.close();
        }

        final IQ pingRequest = new IQ( Type.get );
        pingRequest.setChildElement( "ping", IQPingHandler.NAMESPACE );
        pingRequest.setFrom( pair.getLocal() );
        pingRequest.setTo( domain );

        // Intercept logging.
        final Writer logs = new StringWriter();
        final String appenderName = addAppender( logs );

        // Intercept packets.
        final PacketInterceptor interceptor = new S2SInterceptor( pingRequest );
        InterceptorManager.getInstance().addInterceptor(interceptor);

        // Send ping.
        try
        {
            Log.info( "Sending server to server ping request to " + domain );
            XMPPServer.getInstance().getIQRouter().route( pingRequest );

            // Wait for success or exceed socket timeout.
            waitUntil.tryAcquire( RemoteServerManager.getSocketTimeout(), TimeUnit.MILLISECONDS );

            // Check on the connection status.
            logSessionStatus(results);

            // Prepare response.
            results.put( "certs", getCertificates() );
            results.put( "stanzas", interceptor.toString() );
            results.put( "logs", logs.toString() );
            results.put( "software", getSoftwareInformation() );

            return results;
        }
        finally
        {
            // Cleanup
            InterceptorManager.getInstance().removeInterceptor( interceptor );
            removeAppender( appenderName );
        }
    }

    String addAppender(final Writer writer) {
        final String name = "openfire-s2s-test-appender-" + StringUtils.randomString( 10 );
        final LoggerContext context = LoggerContext.getContext(false);
        final Configuration config = context.getConfiguration();
        final PatternLayout layout = PatternLayout.createDefaultLayout(config);
        final Appender appender = WriterAppender.createAppender(layout, null, writer, name, false, true);
        appender.start();
        config.addAppender(appender);

        final Level level = null;
        final Filter filter = null;
        for (final LoggerConfig loggerConfig : config.getLoggers().values()) {
            loggerConfig.addAppender(appender, level, filter);
        }
        config.getRootLogger().addAppender(appender, level, filter);
        return name;
    }

    void removeAppender(final String name) {
        final LoggerContext context = LoggerContext.getContext(false);
        final Configuration config = context.getConfiguration();
        config.getAppenders().remove( name ).stop();

        for (final LoggerConfig loggerConfig : config.getLoggers().values()) {
            loggerConfig.removeAppender( name );
        }
        config.getRootLogger().removeAppender( name );
    }


    /**
     * Logs the status of the session.
     */
    private void logSessionStatus(Map<String, String> results) {
        final DomainPair pair = new DomainPair(XMPPServer.getInstance().getServerInfo().getXMPPDomain(), domain);
        OutgoingServerSession session = XMPPServer.getInstance().getSessionManager().getOutgoingServerSession(pair);
        if (session != null) {
            Log.info("Session is {}.", session.getStatus());
            results.put("status", session.getStatus().toString());
        } else {
            Log.info("Failed to establish server to server session.");
            results.put("status", "Failed");
        }
    }

    /**
     * @return A String representation of the certificate chain for the connection to the domain under test.
     */
    private String getCertificates() {
        final DomainPair pair = new DomainPair(XMPPServer.getInstance().getServerInfo().getXMPPDomain(), domain);
        Session session = XMPPServer.getInstance().getSessionManager().getOutgoingServerSession(pair);
        StringBuilder certs = new StringBuilder();
        if (session != null) {
            Log.info("Successfully negotiated TLS connection.");
            Certificate[] certificates = session.getPeerCertificates();
            for (Certificate certificate : certificates) {
                X509Certificate x509cert = (X509Certificate) certificate;
                certs.append("--\nSubject: ");
                certs.append(x509cert.getSubjectDN());

                List<String> subjectAltNames = new SANCertificateIdentityMapping().mapIdentity(x509cert);
                if (!subjectAltNames.isEmpty()) {
                    certs.append("\nSubject Alternative Names: ");
                    for (String subjectAltName : subjectAltNames) {
                        certs.append("\n  ");
                        certs.append(subjectAltName);
                    }
                }

                certs.append("\nNot Before: ");
                certs.append(x509cert.getNotBefore());
                certs.append("\nNot After: ");
                certs.append(x509cert.getNotAfter());
                certs.append("\n\n-----BEGIN CERTIFICATE-----\n");
                certs.append(DatatypeConverter.printBase64Binary(
                        certificate.getPublicKey().getEncoded()).replaceAll("(.{64})", "$1\n"));
                certs.append("\n-----END CERTIFICATE-----\n\n");
            }
        }
        return certs.toString();
    }

    private String getSoftwareInformation(){
        SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
        List<IncomingServerSession> inSessions = sessionManager.getIncomingServerSessions(domain);
        List<OutgoingServerSession> outSessions = sessionManager.getOutgoingServerSessions(domain);
        if(inSessions.isEmpty() && outSessions.isEmpty()){
            Log.debug("No sessions for " + domain);
            return "";
        }

        Predicate<ServerSession> hasSoftwareInformation = s -> !s.getSoftwareVersion().isEmpty();
        List<IncomingServerSession> inSessionsWithSoftwareInformation = inSessions.stream().filter(hasSoftwareInformation).collect(Collectors.toList());
        List<OutgoingServerSession> outSessionsWithSoftwareInformation = outSessions.stream().filter(hasSoftwareInformation).collect(Collectors.toList());

        if(inSessionsWithSoftwareInformation.isEmpty() && outSessionsWithSoftwareInformation.isEmpty()){
            Log.debug("No sessions with software information for " + domain);
            return "";
        }


        LocalSession session = (LocalSession) inSessionsWithSoftwareInformation.toArray()[0];
        if (session != null && !session.getSoftwareVersion().isEmpty()){
            String software = getSoftwareFromSession(session);
            if (software != null) return software;
        }

        session = (LocalSession) outSessionsWithSoftwareInformation.toArray()[0];
        if (session != null && !session.getSoftwareVersion().isEmpty()){
            String software = getSoftwareFromSession(session);
            if (software != null) return software;
        }

        Log.debug("No useful version info found for " + domain);
        return "";
    }

    private static String getSoftwareFromSession(LocalSession session) {
        Map<String,String> softwareVersioninfo = session.getSoftwareVersion();
        if(!softwareVersioninfo.get("name").isEmpty()){
            StringBuilder software = new StringBuilder();
            software.append(softwareVersioninfo.get("name"));
            software.append(" ");
            software.append(softwareVersioninfo.get("version"));
            return software.toString();
        }
        return null;
    }

    /**
     * Packet interceptor for the duration of our S2S test.
     */
    private class S2SInterceptor implements PacketInterceptor {
        private StringBuilder xml = new StringBuilder();

        private final IQ ping;

        /**
         * @param ping The IQ ping request that was used to initiate the test.
         */
        public S2SInterceptor( IQ ping )
        {
            this.ping = ping;
        }

        /**
         * Keeps a log of the XMPP traffic, releasing the wait lock on response received.
         */
        @Override
        public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed)
                throws PacketRejectedException {

            if (ping.getTo() == null || packet.getFrom() == null || packet.getTo() == null) {
                return;
            }

            if (!processed
                    && (ping.getTo().getDomain().equals(packet.getFrom().getDomain()) || ping.getTo().getDomain().equals(packet.getTo().getDomain()))) {

                // Log all traffic to and from the domain.
                xml.append(packet.toXML());
                xml.append('\n');

                // If we've received our IQ response, stop the test.
                if ( packet instanceof IQ )
                {
                    final IQ iq = (IQ) packet;
                    if ( iq.isResponse() && ping.getID().equals( iq.getID() ) && ping.getTo().equals( iq.getFrom() ) ) {
                        Log.info("Successful server to server response received.");
                        waitUntil.release();
                    }
                }
            }
        }

        /**
         * Returns the received stanzas as a String.
         */
        public String toString() {
            return xml.toString();
        }
    }
}
