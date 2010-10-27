/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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
package org.jboss.as.test.domain.xml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;

import junit.framework.TestCase;

import org.jboss.as.model.AbstractDomainModelUpdate;
import org.jboss.as.model.AbstractHostModelUpdate;
import org.jboss.as.model.AbstractServerModelUpdate;
import org.jboss.as.model.DomainModel;
import org.jboss.as.model.Element;
import org.jboss.as.model.HostModel;
import org.jboss.as.model.ModelXmlParsers;
import org.jboss.as.model.Namespace;
import org.jboss.as.model.ServerModel;
import org.jboss.as.version.Version;
import org.jboss.modules.ModuleLoadException;
import org.jboss.staxmapper.XMLContentWriter;
import org.jboss.staxmapper.XMLExtendedStreamWriter;
import org.jboss.staxmapper.XMLMapper;

/**
 * A XSDValidationUnitTestCase.
 *
 * @author Brian Stansberry
 * @version $Revision: 1.1 $
 */
public class StandardConfigsXMLParsingMarshallingUnitTestCase extends TestCase {


    private String modulePath = null;
//    pr {
//        try {
//            MODULE_ROOTS = new File[1];
//            File file = new File(StandardConfigsXMLParsingMarshallingUnitTestCase.class.getResource("/").toURI());
//            file = file.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
//            file = new File(file, "build");
//            file = new File(file, "target");
//            file = new File(file, "modules");
//            if (!file.exists())
//                throw new FileNotFoundException(file.getAbsolutePath());
//            MODULE_ROOTS[0] = file;
//
//
//            moduleLoader = new LocalModuleLoader(StandardConfigsXMLParsingMarshallingUnitTestCase.class.getSimpleName(), MODULE_ROOTS);
//            Module.setModuleLoaderSelector(new SimpleModuleLoaderSelector(moduleLoader));
//        } catch (Exception e) {
//            // AutoGenerated
//            throw new RuntimeException(e);
//        }
//    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        modulePath = System.getProperty("module.path");
        File file = new File(getASHome());
        file = new File(file, "modules");
        if (!file.exists())
            throw new FileNotFoundException(file.getAbsolutePath());
        System.setProperty("module.path", file.getAbsolutePath());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (modulePath == null)
            System.clearProperty("module.path");
        else
            System.setProperty("module.path", modulePath);
    }

    public void testHost() throws Exception {
        URL url = getXmlUrl("domain/configuration/host.xml");
        Reader reader = getReader(url);
        HostModel model = parseHost(reader);
        String xml = writeModel(Element.HOST, model);
        reader = new StringReader(xml);
        parseHost(reader);
    }

    public void testDomain() throws Exception {
        URL url = getXmlUrl("domain/configuration/domain.xml");
        Reader reader = getReader(url);
        DomainModel model = parseDomain(reader);
        String xml = writeModel(Element.DOMAIN, model);
        reader = new StringReader(xml);
        parseDomain(reader);
    }

    public void testStandalone() throws Exception {
        URL url = getXmlUrl("standalone/configuration/standalone.xml");
        Reader reader = getReader(url);
        ServerModel model = parseServer(reader);
        String xml = writeModel(Element.SERVER, model);
        reader = new StringReader(xml);
        parseServer(reader);
    }

    private DomainModel parseDomain(final Reader reader) throws ModuleLoadException {
        final XMLMapper mapper = XMLMapper.Factory.create();
        registerStandardDomainReaders(mapper);
        try {
            final List<AbstractDomainModelUpdate<?>> domainUpdates = new ArrayList<AbstractDomainModelUpdate<?>>();
            mapper.parseDocument(domainUpdates, XMLInputFactory.newInstance().createXMLStreamReader(new BufferedReader(reader)));
            final DomainModel domainModel = new DomainModel();
            for(final AbstractDomainModelUpdate<?> update : domainUpdates) {
                domainModel.update(update);
            }
            return domainModel;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Caught exception during processing of domain.xml", e);
        }
    }

    private HostModel parseHost(final Reader reader) throws ModuleLoadException {
        final XMLMapper mapper = XMLMapper.Factory.create();
        registerStandardHostReaders(mapper);
        try {
            final List<AbstractHostModelUpdate<?>> hostUpdates = new ArrayList<AbstractHostModelUpdate<?>>();
            mapper.parseDocument(hostUpdates, XMLInputFactory.newInstance().createXMLStreamReader(new BufferedReader(reader)));
            final HostModel hostModel = new HostModel();
            for(final AbstractHostModelUpdate<?> update : hostUpdates) {
                hostModel.update(update);
            }
            return hostModel;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Caught exception during processing of host.xml", e);
        }
    }

    private ServerModel parseServer(final Reader reader) throws ModuleLoadException {
        final XMLMapper mapper = XMLMapper.Factory.create();
        registerStandardServerReaders(mapper);
        try {
            final List<AbstractServerModelUpdate<?>> serverUpdates = new ArrayList<AbstractServerModelUpdate<?>>();
            mapper.parseDocument(serverUpdates, XMLInputFactory.newInstance().createXMLStreamReader(new BufferedReader(reader)));
            final ServerModel serverModel = new ServerModel();
            for(final AbstractServerModelUpdate<?> update : serverUpdates) {
                serverModel.update(update);
            }
            return serverModel;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Caught exception during processing of standalone.xml", e);
        }
    }

    private String writeModel(final Element element, final XMLContentWriter content) throws XMLStreamException, FactoryConfigurationError {
        final XMLMapper mapper = XMLMapper.Factory.create();
        final StringWriter writer = new StringWriter();
        try {
            mapper.deparseDocument(new RootElementWriter(element, content), XMLOutputFactory.newInstance().createXMLStreamWriter(writer));
        }
        catch (XMLStreamException e) {
            // Dump some diagnostics
            System.out.println("XML Content that was written prior to exception:");
            System.out.println(writer.toString());
            throw e;
        }
        return writer.toString();
    }

    private synchronized void registerStandardDomainReaders(XMLMapper mapper) throws ModuleLoadException {
        mapper.registerRootElement(new QName(Namespace.CURRENT.getUriString(), Element.DOMAIN.getLocalName()), ModelXmlParsers.DOMAIN_XML_READER);
    }

    private synchronized void registerStandardHostReaders(XMLMapper mapper) throws ModuleLoadException {
        mapper.registerRootElement(new QName(Namespace.CURRENT.getUriString(), Element.HOST.getLocalName()), ModelXmlParsers.HOST_XML_READER);
    }

    private synchronized void registerStandardServerReaders(XMLMapper mapper) throws ModuleLoadException {
        mapper.registerRootElement(new QName(Namespace.CURRENT.getUriString(), Element.SERVER.getLocalName()), ModelXmlParsers.SERVER_XML_READER);
    }

    private URL getXmlUrl(String xmlName) throws MalformedURLException {
        // user.dir will point to the root of this module
        File f = new File(getASHome());
        f = new File(f, xmlName);
        return f.toURI().toURL();
    }

    private Reader getReader(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        InputStream is = connection.getInputStream();
        InputStreamReader isr = new InputStreamReader(is);
        return isr;
    }

    private class RootElementWriter implements XMLContentWriter {

        private final Element element;
        private final XMLContentWriter content;

        RootElementWriter(final Element element, final XMLContentWriter content) {
            this.element = element;
            this.content = content;
        }

        @Override
        public void writeContent(XMLExtendedStreamWriter streamWriter) throws XMLStreamException {
            streamWriter.writeStartDocument();
            streamWriter.writeStartElement(element.getLocalName());
            content.writeContent(streamWriter);
            streamWriter.writeEndDocument();
        }

    }

    protected static String getASHome() {
       File f = new File(".");
       f = f.getAbsoluteFile();
       while(f.getParentFile() != null) {
          if("testsuite".equals(f.getName())) {
             assertNotNull("Expected to find a parent directory for " + f.getAbsolutePath(), f.getParentFile());
             f = f.getParentFile();
             f = new File(f, "build");
             assertTrue("The server 'build' dir exists", f.exists());
             f = new File(f, "target");
             f = new File(f, Version.AS_VERSION);
             if(!f.exists())
                fail("The server hasn't been built yet.");
             assertTrue("The server 'build/target' dir exists", f.exists());
             return f.getAbsolutePath();
          } else {
             f = f.getParentFile();
          }
       }
       return null;
    }
}