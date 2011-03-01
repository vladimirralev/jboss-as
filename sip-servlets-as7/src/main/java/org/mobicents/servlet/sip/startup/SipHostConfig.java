/*
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
package org.mobicents.servlet.sip.startup;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.catalina.startup.HostConfig;
import org.apache.log4j.Logger;
import org.mobicents.servlet.sip.annotations.SipApplicationAnnotationUtils;

/**
 * @author Jean Deruelle
 *
 */
public class SipHostConfig extends HostConfig {
	private static final String WAR_EXTENSION = ".war";
	private static final String SAR_EXTENSION = ".sar";
	public static final String SIP_CONTEXT_CLASS = "org.mobicents.servlet.sip.startup.SipStandardContext";
	public static final String SIP_CONTEXT_CONFIG_CLASS = "org.mobicents.servlet.sip.startup.SipContextConfig";
	
	private static final Logger logger = Logger.getLogger(SipHostConfig.class);
	/**
	 * 
	 */
	public SipHostConfig() {
		super();				
	}
		
	//completely overwritten since jboss web in jboss as5 and as 4.2.3 are not based on tomcat 6.0.20 
	@Override
    protected String getDocBase(String path) {
        String basename = null;
        if (path.equals("")) {
            basename = "ROOT";
        } else {
            basename = path.substring(1).replace('/', '#');
        }
        return (basename);
    }
	
	/**
	 * Check if the file given in parameter match a sip servlet application, i.e.
	 * if it contains a sip.xml in its WEB-INF directory
	 * @param file the file to check (war or sar)
	 * @return true if the file is a sip servlet application, false otherwise
	 */
	private boolean isSipServletArchive(File file) {
		if (file.getName().toLowerCase().endsWith(SAR_EXTENSION)) {
			return true;
		} else if (file.getName().toLowerCase().endsWith(WAR_EXTENSION)) {
			try{
                JarFile jar = new JarFile(file);                                  
                JarEntry entry = jar.getJarEntry(SipContext.APPLICATION_SIP_XML);
                if(entry != null) {
                        return true;
                }                
	        } catch (IOException e) {
	        	if(logger.isInfoEnabled()) {
	        		logger.info("couldn't find WEB-INF/sip.xml in " + file + " checking for package-info.class");
	        	}
	        }
			return SipApplicationAnnotationUtils.findPackageInfoInArchive(file);
		} 		
		return false;
	}

	/**
	 * Check if the file given in parameter match a sip servlet application, i.e.
	 * if it contains a sip.xml in its WEB-INF directory
	 * @param file the file to check (war or sar)
	 * @return true if the file is a sip servlet application, false otherwise
	 */
	private boolean isSipServletDirectory(File dir) {
		 if(dir.isDirectory()) {
			 //Fix provided by Thomas Leseney for exploded directory deployments
			File sipXmlFile = new File(dir.getAbsoluteFile(), SipContext.APPLICATION_SIP_XML);
			if(sipXmlFile.exists()) {
				return true;
			}
			if(SipApplicationAnnotationUtils.findPackageInfoinDirectory(dir)) return true;
		}		
		return false;
	}

}
