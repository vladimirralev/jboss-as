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
package org.mobicents.servlet.sip.message;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.servlet.sip.Address;
import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.sip.ListeningPoint;
import javax.sip.SipException;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentDispositionHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;

import org.apache.log4j.Logger;
import org.mobicents.ha.javax.sip.SipLoadBalancer;
import org.mobicents.servlet.sip.JainSipUtils;
import org.mobicents.servlet.sip.SipFactories;
import org.mobicents.servlet.sip.address.AddressImpl;
import org.mobicents.servlet.sip.address.GenericURIImpl;
import org.mobicents.servlet.sip.address.SipURIImpl;
import org.mobicents.servlet.sip.address.TelURLImpl;
import org.mobicents.servlet.sip.address.URIImpl;
import org.mobicents.servlet.sip.core.ApplicationRoutingHeaderComposer;
import org.mobicents.servlet.sip.core.ExtendedListeningPoint;
import org.mobicents.servlet.sip.core.SipApplicationDispatcher;
import org.mobicents.servlet.sip.core.SipNetworkInterfaceManager;
import org.mobicents.servlet.sip.core.dispatchers.MessageDispatcher;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.core.session.SessionManagerUtil;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionKey;
import org.mobicents.servlet.sip.core.session.SipManager;
import org.mobicents.servlet.sip.core.session.SipSessionKey;
import org.mobicents.servlet.sip.security.AuthInfoImpl;
import org.mobicents.servlet.sip.startup.SipContext;
import org.mobicents.servlet.sip.startup.StaticServiceHolder;

public class SipFactoryImpl implements Externalizable {	

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(SipFactoryImpl.class
			.getCanonicalName());
	private static final String TAG_PARAM = "tag";
	private static final String METHOD_PARAM = "method";
	private static final String MADDR_PARAM = "maddr";
	private static final String TTL_PARAM = "ttl";
	private static final String TRANSPORT_PARAM = "transport";
	private static final String LR_PARAM = "lr";

	private boolean useLoadBalancer = false;
	private SipLoadBalancer loadBalancerToUse = null;
	
	public static class NamesComparator implements Comparator<String>, Serializable {		
		private static final long serialVersionUID = 1L;

		public int compare(String o1, String o2) {
			return o1.compareToIgnoreCase(o2);
		}
	}
	
	public static final Set<String> FORBIDDEN_PARAMS = new HashSet<String>();

	static {
		FORBIDDEN_PARAMS.add(TAG_PARAM);
		FORBIDDEN_PARAMS.add(METHOD_PARAM);
		FORBIDDEN_PARAMS.add(MADDR_PARAM);
		FORBIDDEN_PARAMS.add(TTL_PARAM);
		FORBIDDEN_PARAMS.add(TRANSPORT_PARAM);
		FORBIDDEN_PARAMS.add(LR_PARAM);
	}	

	private transient SipApplicationDispatcher sipApplicationDispatcher = null;
	
	public SipFactoryImpl() {}
	/**
	 * Dafault constructor
	 * @param sipApplicationDispatcher 
	 */
	public SipFactoryImpl(SipApplicationDispatcher sipApplicationDispatcher) {		
		this.sipApplicationDispatcher = sipApplicationDispatcher;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createAddress(java.lang.String)
	 */
	public Address createAddress(String sipAddress)
			throws ServletParseException {
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("Creating Address from [" + sipAddress + "]");
			}

			AddressImpl retval = new AddressImpl();
			retval.setValue(sipAddress);
			return retval;
		} catch (IllegalArgumentException e) {
			throw new ServletParseException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createAddress(javax.servlet.sip.URI)
	 */
	public Address createAddress(URI uri) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating Address fromm URI[" + uri.toString()
					+ "]");
		}
		URIImpl uriImpl = (URIImpl) uri;
		return new AddressImpl(SipFactories.addressFactory
				.createAddress(uriImpl.getURI()), null, true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createAddress(javax.servlet.sip.URI,
	 *      java.lang.String)
	 */
	public Address createAddress(URI uri, String displayName) {
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("Creating Address from URI[" + uri.toString()
						+ "] with display name[" + displayName + "]");
			}

			javax.sip.address.Address address = SipFactories.addressFactory
					.createAddress(((URIImpl) uri).getURI());
			address.setDisplayName(displayName);
			return new AddressImpl(address, null, true);

		} catch (ParseException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createApplicationSession()
	 */
	public SipApplicationSession createApplicationSession() {
		throw new UnsupportedOperationException("use createApplicationSession(SipContext sipContext) instead !");
	}
	
	/**
	 * Creates an application session associated with the context
	 * @param sipContext
	 * @return
	 */
	public SipApplicationSession createApplicationSession(SipContext sipContext) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating new application session for sip context "+ sipContext.getApplicationName());
		}
		//call id not needed anymore since the sipappsessionkey is not a callid anymore but a random uuid
		SipApplicationSessionKey sipApplicationSessionKey = SessionManagerUtil.getSipApplicationSessionKey(
				sipContext.getApplicationName(), 
				null);		
		MobicentsSipApplicationSession sipApplicationSession = ((SipManager)sipContext.getManager()).getSipApplicationSession(
				sipApplicationSessionKey, true);
		
		if(StaticServiceHolder.sipStandardService.isHttpFollowsSip()) {
			String jvmRoute = StaticServiceHolder.sipStandardService.getJvmRoute();
			if(jvmRoute == null) {
				sipApplicationSession.setJvmRoute(jvmRoute);
			}
		}
			
		return sipApplicationSession.getSession();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createRequest(javax.servlet.sip.SipApplicationSession,
	 *      java.lang.String, javax.servlet.sip.Address,
	 *      javax.servlet.sip.Address)
	 */
	public SipServletRequest createRequest(SipApplicationSession sipAppSession,
			String method, Address from, Address to, String handler, String originalCallId, String fromTagToUse) {
		if (logger.isDebugEnabled()) {
			logger
					.debug("Creating new SipServletRequest for SipApplicationSession["
							+ sipAppSession
							+ "] METHOD["
							+ method
							+ "] FROM_A[" + from + "] TO_A[" + to + "]");
		}

		validateCreation(method, sipAppSession);

		try { 
			//javadoc specifies that a copy of the address should be done hence the clone
			return createSipServletRequest(sipAppSession, method, (Address)from.clone(), (Address)to.clone(), handler, originalCallId, fromTagToUse);
		} catch (ServletParseException e) {
			logger.error("Error creating sipServletRequest", e);
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createRequest(javax.servlet.sip.SipApplicationSession,
	 *      java.lang.String, javax.servlet.sip.URI, javax.servlet.sip.URI)
	 */
	public SipServletRequest createRequest(SipApplicationSession sipAppSession,
			String method, URI from, URI to, String handler) {
		if (logger.isDebugEnabled()) {
			logger
					.debug("Creating new SipServletRequest for SipApplicationSession["
							+ sipAppSession
							+ "] METHOD["
							+ method
							+ "] FROM_URI[" + from + "] TO_URI[" + to + "]");
		}

		validateCreation(method, sipAppSession);

		//javadoc specifies that a copy of the uri should be done hence the clone
		Address toA = this.createAddress(to.clone());
		Address fromA = this.createAddress(from.clone());

		try {
			return createSipServletRequest(sipAppSession, method, fromA, toA, handler, null, null);
		} catch (ServletParseException e) {
			logger.error("Error creating sipServletRequest", e);
			return null;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createRequest(javax.servlet.sip.SipApplicationSession,
	 *      java.lang.String, java.lang.String, java.lang.String)
	 */
	public SipServletRequest createRequest(SipApplicationSession sipAppSession,
			String method, String from, String to, String handler) throws ServletParseException {
		if (logger.isDebugEnabled()) {
			logger
					.debug("Creating new SipServletRequest for SipApplicationSession["
							+ sipAppSession
							+ "] METHOD["
							+ method
							+ "] FROM["
							+ from + "] TO[" + to + "]");
		}

		validateCreation(method, sipAppSession);

		Address toA = this.createAddress(to);
		Address fromA = this.createAddress(from);

		return createSipServletRequest(sipAppSession, method, fromA, toA, handler, null, null);

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createRequest(javax.servlet.sip.SipServletRequest,
	 *      boolean)
	 */
	public SipServletRequest createRequest(SipServletRequest origRequest,
			boolean sameCallId) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating SipServletRequest from original request["
					+ origRequest + "] with same call id[" + sameCallId + "]");
		}

	    return origRequest.getB2buaHelper().createRequest(origRequest);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.sip.SipFactory#createSipURI(java.lang.String,
	 *      java.lang.String)
	 */
	public SipURI createSipURI(String user, String host) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating SipURI from USER[" + user + "] HOST[" + host
					+ "]");
		}
		try {
			return new SipURIImpl(SipFactories.addressFactory.createSipURI(
					user, host));
		} catch (ParseException e) {
			logger.error("couldn't parse the SipURI from USER[" + user
					+ "] HOST[" + host + "]", e);
			throw new IllegalArgumentException("Could not create SIP URI user = " + user + " host = " + host);
		}
	}

	public URI createURI(String uri) throws ServletParseException {
//		if(!checkScheme(uri)) {
//			// testCreateProxyBranches101 needs this to be IllegalArgumentExcpetion, but the test is wrong
//			throw new ServletParseException("The uri " + uri + " is not valid");
//		}
		try {
			javax.sip.address.URI jainUri = SipFactories.addressFactory
					.createURI(uri);
			if (jainUri instanceof javax.sip.address.SipURI) {
				return new SipURIImpl(
						(javax.sip.address.SipURI) jainUri);
			} else if (jainUri instanceof javax.sip.address.TelURL) {
				return new TelURLImpl(
						(javax.sip.address.TelURL) jainUri);
			} else {
				return new GenericURIImpl(jainUri);
			}
		} catch (ParseException ex) {
			throw new ServletParseException("Bad param " + uri, ex);
		}
	}

	// ------------ HELPER METHODS
	// -------------------- createRequest
	/**
	 * Does basic check for illegal methods, wrong state, if it finds, it throws
	 * exception
	 * 
	 */
	private static void validateCreation(String method, SipApplicationSession app) {

		if (method.equals(Request.ACK)) {
			throw new IllegalArgumentException(
					"Wrong method to create request with[" + Request.ACK + "]!");
		}
		if (method.equals(Request.PRACK)) {
			throw new IllegalArgumentException(
					"Wrong method to create request with[" + Request.PRACK + "]!");
		}
		if (method.equals(Request.CANCEL)) {
			throw new IllegalArgumentException(
					"Wrong method to create request with[" + Request.CANCEL
							+ "]!");
		}
		if (!((MobicentsSipApplicationSession)app).isValidInternal()) {
			throw new IllegalArgumentException(
					"Cant associate request with invalidaded sip session application!");
		}

	}

	/**
	 * This method actually does create javax.sip.message.Request, dialog(if
	 * method is INVITE or SUBSCRIBE), ctx and wraps this in new sipsession
	 * 
	 * @param sipAppSession
	 * @param method
	 * @param from
	 * @param to
	 * @param originalCallId 
	 * @return
	 */
	private SipServletRequest createSipServletRequest(
			SipApplicationSession sipAppSession, String method, Address from,
			Address to, String handler, String originalCallId, String fromTagToUse) throws ServletParseException {
		
		MobicentsSipApplicationSession mobicentsSipApplicationSession = (MobicentsSipApplicationSession) sipAppSession;
		
		// the request object with method, request URI, and From, To, Call-ID,
		// CSeq, Route headers filled in.
		Request requestToWrap = null;

		ContactHeader contactHeader = null;
		ToHeader toHeader = null;
		FromHeader fromHeader = null;
		CSeqHeader cseqHeader = null;
		CallIdHeader callIdHeader = null;
		MaxForwardsHeader maxForwardsHeader = null;		

		// FIXME: Is this nough?
		// We need address from which this will be sent, also this one will be
		// default for contact and via
		String transport = ListeningPoint.UDP;

		// LETS CREATE OUR HEADERS			
		javax.sip.address.Address fromAddress = null;
		try {
			// Issue 676 : Any component of the from and to URIs not allowed in the context of
			// SIP From and To headers are removed from the copies [refer Table 1, Section
			// 19.1.1, RFC3261]
			for(String param : FORBIDDEN_PARAMS) {
				from.getURI().removeParameter(param);	
			}
			
			// Issue 676 : from tags not removed so removing the tag
			from.removeParameter(TAG_PARAM);
			
			fromAddress = SipFactories.addressFactory
					.createAddress(((URIImpl)from.getURI()).getURI());
			fromAddress.setDisplayName(from.getDisplayName());		
			
			fromHeader = SipFactories.headerFactory.createFromHeader(fromAddress, null);			
		} catch (Exception pe) {
			throw new ServletParseException("Impossoible to parse the given From " + from.toString(), pe);
		}
		javax.sip.address.Address toAddress = null; 
		try{
			// Issue 676 : Any component of the from and to URIs not allowed in the context of
			// SIP From and To headers are removed from the copies [refer Table 1, Section
			// 19.1.1, RFC3261]
			for(String param : FORBIDDEN_PARAMS) {
				to.getURI().removeParameter(param);	
			}
			// Issue 676 : to tags not removed so removing the tag
			to.removeParameter(TAG_PARAM);
			
			toAddress = SipFactories.addressFactory
				.createAddress(((URIImpl)to.getURI()).getURI());
			
			toAddress.setDisplayName(to.getDisplayName());

			toHeader = SipFactories.headerFactory.createToHeader(toAddress, null);										
		} catch (Exception pe) {
			throw new ServletParseException("Impossoible to parse the given To " + to.toString(), pe);
		}
		try {
			cseqHeader = SipFactories.headerFactory.createCSeqHeader(1L, method);
			// Fix provided by Hauke D. Issue 411
			SipApplicationSessionKey sipApplicationSessionKey = mobicentsSipApplicationSession.getKey();
//			if(sipApplicationSessionKey.isAppGeneratedKey()) {
			if(originalCallId == null) {
				final Iterator<ExtendedListeningPoint> listeningPointsIterator = getSipNetworkInterfaceManager().getExtendedListeningPoints();				
				if(listeningPointsIterator.hasNext()) {
					callIdHeader = SipFactories.headerFactory.createCallIdHeader(
							listeningPointsIterator.next().getSipProvider().getNewCallId().getCallId());
				} else {
					throw new IllegalStateException("There is no SIP connectors available to create the request");
				}
			} else {
				callIdHeader = SipFactories.headerFactory.createCallIdHeader(originalCallId);
			}
//			} else {
//				callIdHeader = SipFactories.headerFactory.createCallIdHeader(
//						sipApplicationSessionKey.getId());
//			}
			maxForwardsHeader = SipFactories.headerFactory
					.createMaxForwardsHeader(JainSipUtils.MAX_FORWARD_HEADER_VALUE);
			URIImpl requestURI = (URIImpl)to.getURI().clone();

			// copying address params into headers.
			// commented out because of Issue 1105
//			Iterator<String> keys = to.getParameterNames();
//
//			while (keys.hasNext()) {
//				String key = keys.next();				
//				toHeader.setParameter(key, to.getParameter(key));
//			}
//
//			keys = from.getParameterNames();
//
//			while (keys.hasNext()) {
//				String key = keys.next();				
//				fromHeader.setParameter(key, from.getParameter(key));
//			}
			//Issue 112 by folsson : no via header to add will be added when the request will be sent out
			List<Header> viaHeaders = new ArrayList<Header>();
						 			
			requestToWrap = SipFactories.messageFactory.createRequest(
					requestURI.getURI(), 
					method, 
					callIdHeader, 
					cseqHeader, 
					fromHeader, 
					toHeader, 
					viaHeaders, 
					maxForwardsHeader);

			//Adding default contact header for specific methods only
			if(JainSipUtils.CONTACT_HEADER_METHODS.contains(method)) {				
				String fromName = null;
				if(fromHeader.getAddress().getURI() instanceof javax.sip.address.SipURI) {
					fromName = ((javax.sip.address.SipURI)fromHeader.getAddress().getURI()).getUser();
				}										
				// Create the contact name address.
				contactHeader = null;
				// if a sip load balancer is present in front of the server, the contact header is the one from the sip lb
				// so that the subsequent requests can be failed over
				if(useLoadBalancer) {
					javax.sip.address.SipURI sipURI = SipFactories.addressFactory.createSipURI(fromName, loadBalancerToUse.getAddress().getHostAddress());
					sipURI.setHost(loadBalancerToUse.getAddress().getHostAddress());
					sipURI.setPort(loadBalancerToUse.getSipPort());			
					sipURI.setTransportParam(transport);
					javax.sip.address.Address contactAddress = SipFactories.addressFactory.createAddress(sipURI);
					if(fromName != null && fromName.length() > 0) {
						contactAddress.setDisplayName(fromName);
					}
					contactHeader = SipFactories.headerFactory.createContactHeader(contactAddress);													
				} else {
					contactHeader = JainSipUtils.createContactHeader(getSipNetworkInterfaceManager(), requestToWrap, fromName, null);
				}
			}
			// Add all headers		
			if(contactHeader != null) {
				requestToWrap.addHeader(contactHeader);
			}
						
			if(fromTagToUse == null) {
				fromHeader.setTag(ApplicationRoutingHeaderComposer.getHash(sipApplicationDispatcher, sipAppSession.getApplicationName(), sipApplicationSessionKey.getId()));
			} else {
				fromHeader.setTag(fromTagToUse);
			}
			
			SipSessionKey key = SessionManagerUtil.getSipSessionKey(
					mobicentsSipApplicationSession.getKey().getId(), mobicentsSipApplicationSession.getKey().getApplicationName(), requestToWrap, false);
			MobicentsSipSession session = ((SipManager)mobicentsSipApplicationSession.getSipContext().getManager()).
				getSipSession(key, true, this, mobicentsSipApplicationSession);
			session.setHandler(handler);
			session.setLocalParty(new AddressImpl(fromAddress, null, false));
			session.setRemoteParty(new AddressImpl(toAddress, null, false));
			
			SipServletRequest retVal = new SipServletRequestImpl(
					requestToWrap, this, session, null, null,
					JainSipUtils.DIALOG_CREATING_METHODS.contains(method));						
			
			return retVal;
		} catch (Exception e) {
			throw new IllegalStateException("Error creating sipServletRequest", e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Parameterable createParameterable(String value) throws ServletParseException {
		try {			 
			Header header = SipFactories.headerFactory.createHeader(ContactHeader.NAME, value);
			return SipServletMessageImpl.createParameterable(header, SipServletMessageImpl.getFullHeaderName(header.getName()));
		} catch (ParseException e) {
			try {
				Header header = SipFactories.headerFactory.createHeader(ContentTypeHeader.NAME, value);
				return SipServletMessageImpl.createParameterable(header, SipServletMessageImpl.getFullHeaderName(header.getName()));
			} catch (ParseException pe) {
				// Contribution from Nishihara, Naoki from Japan for Issue http://code.google.com/p/mobicents/issues/detail?id=1856
				// Cannot create a parameterable header for Session-Expires
				try {
					Header header = SipFactories.headerFactory.createHeader(ContentDispositionHeader.NAME, value);
					return SipServletMessageImpl.createParameterable(header, SipServletMessageImpl.getFullHeaderName(header.getName()));
				} catch (ParseException pe2) {
					throw new ServletParseException("Impossible to parse the following parameterable "+ value , pe2);
				}
			}
		} 		
	}	
	
	/**
	 * {@inheritDoc}
	 */
	public SipApplicationRouterInfo getNextInterestedApplication(SipServletRequestImpl sipServletRequestImpl) {
		return sipApplicationDispatcher.getNextInterestedApplication(sipServletRequestImpl);
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.SipFactory#createApplicationSessionByAppName(java.lang.String)
	 */
	public SipApplicationSession createApplicationSessionByAppName(
			String sipAppName) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating new application session for application name " + sipAppName);
		}
		SipContext sipContext = sipApplicationDispatcher.findSipApplication(sipAppName);
		if(sipContext == null) {
			throw new IllegalArgumentException("The specified application "+sipAppName+" is not currently deployed");
		}
		return createApplicationSession(sipContext);
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.SipFactory#createApplicationSessionByKey(java.lang.String)
	 */
	public SipApplicationSession createApplicationSessionByKey(
			String sipApplicationKey) {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating new application session by following key " + sipApplicationKey);
		}
		SipApplicationSessionKey sipApplicationSessionKey = null;
		try {
			sipApplicationSessionKey = SessionManagerUtil.parseSipApplicationSessionKey(
					sipApplicationKey);
		} catch (ParseException e) {
			throw new IllegalArgumentException(sipApplicationKey + " is not a valid sip application session key", e);
		}		
		SipContext sipContext = sipApplicationDispatcher.findSipApplication(sipApplicationSessionKey.getApplicationName());
		if(sipContext == null) {
			throw new IllegalArgumentException("The specified application "+sipApplicationSessionKey.getApplicationName()+" is not currently deployed");
		}
		MobicentsSipApplicationSession sipApplicationSession = ((SipManager)sipContext.getManager()).getSipApplicationSession(
				sipApplicationSessionKey, true);		
		return sipApplicationSession.getSession();
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.SipFactory#createAuthInfo()
	 */
	public AuthInfo createAuthInfo() {
		return new AuthInfoImpl();
	}

	/**
	 * @return the sipApplicationDispatcher
	 */
	public SipApplicationDispatcher getSipApplicationDispatcher() {
		return sipApplicationDispatcher;
	}

	/**
	 * @param sipApplicationDispatcher the sipApplicationDispatcher to set
	 */
	public void setSipApplicationDispatcher(
			SipApplicationDispatcher sipApplicationDispatcher) {
		this.sipApplicationDispatcher = sipApplicationDispatcher;
	}
	
	/**
	 * Retrieve the manager for the sip network interfaces
	 * @return the manager for the sip network interfaces
	 */
	public SipNetworkInterfaceManager getSipNetworkInterfaceManager() {
		return sipApplicationDispatcher.getSipNetworkInterfaceManager();
	}

	/**
	 * @return the loadBalancerToUse
	 */
	public SipLoadBalancer getLoadBalancerToUse() {
		return loadBalancerToUse;
	}

	/**
	 * @param loadBalancerToUse the loadBalancerToUse to set
	 */
	public void setLoadBalancerToUse(SipLoadBalancer loadBalancerToUse) {		
		if(loadBalancerToUse == null) {
			useLoadBalancer = false;
		} else {
			useLoadBalancer = true;
		}
		this.loadBalancerToUse = loadBalancerToUse;
		if(logger.isInfoEnabled()) {
			logger.info("Load Balancer to Use " + loadBalancerToUse);
		}
	}

	/**
	 * @return the useLoadBalancer
	 */
	public boolean isUseLoadBalancer() {
		return useLoadBalancer;
	}

	/**
	 * 
	 * @param request
	 * @throws ParseException
	 */
	public void addLoadBalancerRouteHeader(Request request) {
		try {
			String host = null;
			int port = -1; 
			String proxy = StaticServiceHolder.sipStandardService.getOutboundProxy();
			if(proxy == null) {
				host = loadBalancerToUse.getAddress().getHostAddress();
				port = loadBalancerToUse.getSipPort();
			} else {
				int separatorIndex = proxy.indexOf(":");
				if(separatorIndex>0) {
					host = proxy.substring(0, separatorIndex);
					port = Integer.parseInt(proxy.substring(separatorIndex + 1));
				}
			}
			javax.sip.address.SipURI sipUri = SipFactories.addressFactory.createSipURI(null, host);
			sipUri.setPort(port);
			sipUri.setLrParam();
			String transport = JainSipUtils.findTransport(request);
			sipUri.setTransportParam(transport);
			ExtendedListeningPoint listeningPoint = 
				getSipNetworkInterfaceManager().findMatchingListeningPoint(transport, false);
			sipUri.setParameter(MessageDispatcher.ROUTE_PARAM_NODE_HOST, 
					listeningPoint.getHost(JainSipUtils.findUsePublicAddress(getSipNetworkInterfaceManager(), request, listeningPoint)));
			sipUri.setParameter(MessageDispatcher.ROUTE_PARAM_NODE_PORT, 
					"" + listeningPoint.getPort());
			javax.sip.address.Address routeAddress = 
				SipFactories.addressFactory.createAddress(sipUri);
			RouteHeader routeHeader = 
				SipFactories.headerFactory.createRouteHeader(routeAddress);
			request.addFirst(routeHeader);			
		} catch (ParseException e) {
			//this should never happen
			throw new IllegalArgumentException("Impossible to set the Load Balancer Route Header !", e);
		} catch (SipException e) {
			//this should never happen
			throw new IllegalArgumentException("Impossible to set the Load Balancer Route Header !", e);
		}
	}
	
	public void addIpLoadBalancerRouteHeader(Request request, String lbhost, int lbport) {
		try {
			String host = null;
			int port = -1; 
			String proxy = StaticServiceHolder.sipStandardService.getOutboundProxy();
			if(proxy == null) {
				host = lbhost;
				port = lbport;
			} else {
				int separatorIndex = proxy.indexOf(":");
				if(separatorIndex>0) {
					host = proxy.substring(0, separatorIndex);
					port = Integer.parseInt(proxy.substring(separatorIndex + 1));
				}
			}
			javax.sip.address.SipURI sipUri = SipFactories.addressFactory.createSipURI(null, host);
			sipUri.setPort(port);
			sipUri.setLrParam();
			String transport = JainSipUtils.findTransport(request);
			sipUri.setTransportParam(transport);
			ExtendedListeningPoint listeningPoint = 
				getSipNetworkInterfaceManager().findMatchingListeningPoint(transport, false);
			sipUri.setParameter(MessageDispatcher.ROUTE_PARAM_NODE_HOST, 
					listeningPoint.getHost(JainSipUtils.findUsePublicAddress(getSipNetworkInterfaceManager(), request, listeningPoint)));
			sipUri.setParameter(MessageDispatcher.ROUTE_PARAM_NODE_PORT, 
					"" + listeningPoint.getPort());
			javax.sip.address.Address routeAddress = 
				SipFactories.addressFactory.createAddress(sipUri);
			RouteHeader routeHeader = 
				SipFactories.headerFactory.createRouteHeader(routeAddress);
			request.addFirst(routeHeader);			
		} catch (ParseException e) {
			//this should never happen
			throw new IllegalArgumentException("Impossible to set the Load Balancer Route Header !", e);
		} catch (SipException e) {
			//this should never happen
			throw new IllegalArgumentException("Impossible to set the Load Balancer Route Header !", e);
		}
	}

	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		useLoadBalancer = in.readBoolean();
		if(useLoadBalancer) {
			loadBalancerToUse = (SipLoadBalancer) in.readObject();
		}
		sipApplicationDispatcher = StaticServiceHolder.sipStandardService.getSipApplicationDispatcher();
	}

	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeBoolean(useLoadBalancer);
		if(useLoadBalancer) {
			out.writeObject(loadBalancerToUse);
		}
	}
}
