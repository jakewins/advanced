/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.management.impl;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;

import org.neo4j.kernel.KernelData;

public class HotspotManagementSupport extends AdvancedManagementSupport
{
    private static final Logger log = Logger.getLogger( HotspotManagementSupport.class.getName() );

    @Override
    protected JMXServiceURL getJMXServiceURL( KernelData kernel )
    {
        JMXServiceURL url = null;
        try
        {
            Class<?> cal = Class.forName( "sun.management.ConnectorAddressLink" );
            try
            {
                Method importRemoteFrom = cal.getMethod( "importRemoteFrom", int.class );
                @SuppressWarnings( "unchecked" ) Map<String, String> remote = (Map<String, String>) importRemoteFrom.invoke(
                        null, Integer.valueOf( 0 ) );
                url = getUrlFrom( remote );
            }
            catch ( NoSuchMethodException ex )
            {
                // handled by null check
            }
            if ( url == null )
            {
                Method importFrom = cal.getMethod( "importFrom", int.class );
                url = getUrlFrom( (String) importFrom.invoke( null, Integer.valueOf( 0 ) ) );
            }
        }
        catch ( InvocationTargetException e )
        {
            log.log( Level.CONFIG, "Failed to load local JMX connection URL.", e.getTargetException() );
        }
        catch ( LinkageError e )
        {
            log.log( Level.CONFIG, "Failed to load local JMX connection URL.", e );
        }
        catch ( Exception e )
        {
            log.log( Level.CONFIG, "Failed to load local JMX connection URL.", e );
        }
        // No previous connection server -- create one!
        if ( url == null )
        {
            Object portObj = kernel.getParam( "jmx.port" );
            int port = 0;
            if ( portObj instanceof Integer )
            {
                port = ( (Integer) portObj ).intValue();
            }
            else if ( portObj instanceof String )
            {
                try
                {
                    port = Integer.parseInt( (String) portObj );
                }
                catch ( NumberFormatException ok )
                {
                    // handled by 0-check
                }
            }
            if ( port > 0 )
            {
                Object useSslObj = kernel.getParam( "jmx.use_ssl" );
                boolean useSSL = false;
                if ( useSslObj instanceof Boolean )
                {
                    useSSL = ( (Boolean) useSslObj ).booleanValue();
                }
                else if ( useSslObj instanceof String )
                {
                    useSSL = Boolean.parseBoolean( (String) useSslObj );
                }
                log.log( Level.CONFIG, "Creating new MBean server on port %s%s", new Object[] {
                        Integer.valueOf( port ), useSSL ? " using ssl" : "" } );
                JMXConnectorServer server = createServer( port, useSSL );
                if ( server != null )
                {
                    try
                    {
                        server.start();
                    }
                    catch ( IOException e )
                    {
                        log.log( Level.CONFIG, "Failed to start MBean server", e );
                        server = null;
                    }
                    if ( server != null )
                    {
                        try
                        {
                            server.getMBeanServer().registerMBean( server,
                                    KernelProxy.createObjectName( kernel.instanceId(), "JMX Server" ) );
                        }
                        catch ( Exception e )
                        {
                            log.log( Level.CONFIG, "Failed to register MBean server as JMX bean", e );
                        }
                        url = server.getAddress();
                    }
                }
            }
        }
        return url;
    }

    private JMXServiceURL getUrlFrom( Map<String, String> remote )
    {
        Set<Integer> instances = new HashSet<Integer>();
        for ( String key : remote.keySet() )
        {
            if ( key.startsWith( "sun.management.JMXConnectorServer" ) )
            {
                int end = key.lastIndexOf( '.' );
                if ( end < 0 ) continue;
                int start = key.lastIndexOf( '.', end );
                if ( start < 0 ) continue;
                final int id;
                try
                {
                    id = Integer.parseInt( key.substring( start, end ) );
                }
                catch ( NumberFormatException e )
                {
                    continue;
                }
                instances.add( Integer.valueOf( id ) );
            }
        }
        if ( !instances.isEmpty() )
        {
            String prefix = "sun.management.JMXConnectorServer.";
            if ( instances.size() > 1 )
            {
                for ( Object key : instances.toArray() )
                {
                    if ( !remote.containsKey( "sun.management.JMXConnectorServer." + key + ".remoteAddress" ) )
                    {
                        instances.remove( key );
                    }
                }
                if ( instances.contains( Integer.valueOf( 0 ) ) )
                {
                    prefix = prefix + "0.";
                }
            }
            if ( instances.size() == 1 )
            {
                String remoteAddress = remote.get( prefix + instances.iterator().next() + "remoteAddress" );
                try
                {
                    return new JMXServiceURL( remoteAddress );
                }
                catch ( MalformedURLException e )
                {
                    return null;
                }
            }
            else if ( !instances.isEmpty() )
            {
                // TODO: find the appropriate one
            }
        }
        return null;
    }

    private JMXServiceURL getUrlFrom( String url )
    {
        if ( url == null ) return null;
        JMXServiceURL jmxUrl;
        try
        {
            jmxUrl = new JMXServiceURL( url );
        }
        catch ( MalformedURLException e1 )
        {
            return null;
        }
        String host = null;
        try
        {
            host = InetAddress.getLocalHost().getHostAddress();
        }
        catch ( UnknownHostException ok )
        {
            // handled by null check
        }
        if ( host == null )
        {
            host = jmxUrl.getHost();
        }
        try
        {
            return new JMXServiceURL( jmxUrl.getProtocol(), host, jmxUrl.getPort(), jmxUrl.getURLPath() );
        }
        catch ( MalformedURLException e )
        {
            return null;
        }
    }

    private JMXConnectorServer createServer( int port, boolean useSSL )
    {
        MBeanServer server = getMBeanServer();
        final JMXServiceURL url;
        try
        {
            url = new JMXServiceURL( "rmi", null, port );
        }
        catch ( MalformedURLException e )
        {
            log.log( Level.WARNING, "Failed to start JMX Server", e );
            return null;
        }
        Map<String, Object> env = new HashMap<String, Object>();
        if ( useSSL )
        {
            env.put( RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, new SslRMIClientSocketFactory() );
            env.put( RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, new SslRMIServerSocketFactory() );
        }
        try
        {
            return JMXConnectorServerFactory.newJMXConnectorServer( url, env, server );
        }
        catch ( IOException e )
        {
            log.log( Level.WARNING, "Failed to start JMX Server", e );
            return null;
        }
    }
}
