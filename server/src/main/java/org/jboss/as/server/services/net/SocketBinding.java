/*
* JBoss, Home of Professional Open Source
* Copyright 2010, Red Hat Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.as.server.services.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.jboss.msc.service.ServiceName;

/**
 * An encapsulation of socket binding related information.
 *
 * @author Emanuel Muckenhuber
 */
public final class SocketBinding {

    public static final ServiceName JBOSS_BINDING_NAME = ServiceName.JBOSS.append("binding");

    private final String name;
    private volatile int port;
    private volatile boolean isFixedPort;
    private volatile InetAddress multicastAddress;
    private volatile int multicastPort;
    private final NetworkInterfaceBinding networkInterface;
    private final SocketBindingManager socketBindings;

    SocketBinding(final String name, int port, boolean isFixedPort, InetAddress multicastAddress, int multicastPort,
            final NetworkInterfaceBinding networkInterface, SocketBindingManager socketBindings) {
        this.name = name;
        this.port = port;
        this.isFixedPort = isFixedPort;
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.socketBindings = socketBindings;
        this.networkInterface = networkInterface;
    }

    /**
     * Return the name of the SocketBinding used in the configuration
     *
     * @return the SocketBinding configuration name
     */
    public String getName() {
        return name;
    }

    /**
     * Return the resolved {@link InetAddress} for this binding.
     *
     * @return the resolve address
     */
    public InetAddress getAddress() {
        return networkInterface != null ? networkInterface.getAddress() : socketBindings.getDefaultInterfaceAddress();
    }

    /**
     * Get the socket binding manager.
     *
     * @return the socket binding manger
     */
    public SocketBindingManager getSocketBindings() {
        return socketBindings;
    }

    /**
     * Get the socket address.
     *
     * @return the socket address
     */
    public InetSocketAddress getSocketAddress() {
        int port = this.port;
        if (port > 0 && isFixedPort == false) {
            port += socketBindings.getPortOffset();
        }
        return new InetSocketAddress(getAddress(), port);
    }

    /**
     * Get the multicast socket address.
     *
     * @return the multicast address
     */
    public InetSocketAddress getMulticastSocketAddress() {
        if (multicastAddress == null) {
            throw new IllegalStateException("no multicast binding: " + name);
        }
        return new InetSocketAddress(multicastAddress, multicastPort);
    }

    /**
     * Create and bind a socket.
     *
     * @return the socket
     * @throws IOException
     */
    public Socket createSocket() throws IOException {
        final Socket socket = getSocketFactory().createSocket();
        socket.bind(getSocketAddress());
        return socket;
    }

    /**
     * Create and bind a server socket
     *
     * @return the server socket
     * @throws IOException
     */
    public ServerSocket createServerSocket() throws IOException {
        final ServerSocket socket = getServerSocketFactory().createServerSocket();
        socket.bind(getSocketAddress());
        return socket;
    }

    /**
     * Create and bind a server socket.
     *
     * @param backlog the backlog
     * @return the server socket
     * @throws IOException
     */
    public ServerSocket createServerSocket(int backlog) throws IOException {
        final ServerSocket socket = getServerSocketFactory().createServerSocket();
        socket.bind(getSocketAddress(), backlog);
        return socket;
    }

    /**
     * Create and bind a datagram socket.
     *
     * @return the datagram socket
     * @throws SocketException
     */
    public DatagramSocket createDatagramSocket() throws SocketException {
        return socketBindings.createDatagramSocket(name, getMulticastSocketAddress());
    }

    /**
     * Create a multicast socket.
     *
     * @return the multicast socket
     * @throws IOException
     */
    // TODO JBAS-8470 automatically joingGroup
    public MulticastSocket createMulticastSocket() throws IOException {
        return socketBindings.createMulticastSocket(name, getSocketAddress());
    }

    /**
     * Get the {@code ManagedBinding} associated with this {@code SocketBinding}.
     *
     * @return the managed binding if bound, <code>null</code> otherwise
     */
    public ManagedBinding getManagedBinding() {
        final SocketBindingManager.NamedManagedBindingRegistry registry = this.socketBindings.getNamedRegistry();
        return registry.getManagedBinding(name);
    }

    /**
     * Check whether this {@code SocketBinding} is bound. All bound sockets
     * have to be registered at the {@code SocketBindingManager} against which
     * this check is performed.
     *
     * @return true if bound, false otherwise
     */
    public boolean isBound() {
        final SocketBindingManager.NamedManagedBindingRegistry registry = this.socketBindings.getNamedRegistry();
        return registry.isRegistered(name);
    }

    public int getPort() {
        return port;
    }

    void setPort(int port) {
        checkNotBound();
        this.port = port;
    }

    public boolean isFixedPort() {
        return isFixedPort;
    }

    void setFixedPort(boolean fixedPort) {
        checkNotBound();
        isFixedPort = fixedPort;
    }

    public int getMulticastPort() {
        return multicastPort;
    }

    void setMulticastPort(int multicastPort) {
        checkNotBound();
        this.multicastPort = multicastPort;
    }

    public InetAddress getMulticastAddress() {
        return multicastAddress;
    }

    void setMulticastAddress(InetAddress multicastAddress) {
        checkNotBound();
        this.multicastAddress = multicastAddress;
    }

    void checkNotBound() {
        if(isBound()) {
            throw new IllegalStateException("cannot change value while the socket is bound.");
        }
    }

    SocketFactory getSocketFactory() {
        return socketBindings.getSocketFactory();
    }

    ServerSocketFactory getServerSocketFactory() {
        return socketBindings.getServerSocketFactory();
    }

}
