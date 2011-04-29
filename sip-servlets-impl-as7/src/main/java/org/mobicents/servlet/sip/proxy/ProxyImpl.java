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
package org.mobicents.servlet.sip.proxy;

import gov.nist.javax.sip.header.Via;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.header.ContactHeader;
import javax.sip.header.Header;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.mobicents.javax.servlet.sip.ProxyExt;
import org.mobicents.servlet.sip.JainSipUtils;
import org.mobicents.servlet.sip.address.SipURIImpl;
import org.mobicents.servlet.sip.address.TelURLImpl;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.core.timers.ProxyTimerService;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.message.SipServletRequestImpl;
import org.mobicents.servlet.sip.message.SipServletResponseImpl;
import org.mobicents.servlet.sip.proxy.ProxyBranchImpl.TransactionRequest;

/**
 * @author root
 *
 */
public class ProxyImpl implements Proxy, ProxyExt, Externalizable {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.getLogger(ProxyImpl.class);
	
	private SipServletRequestImpl originalRequest;
	private transient SipServletResponseImpl bestResponse;
	private transient ProxyBranchImpl bestBranch;
	private boolean recurse = true;
	private int proxyTimeout;
	private int proxy1xxTimeout;
	private int seqSearchTimeout;
	private boolean supervised = true; 
	private boolean recordRoutingEnabled;
	private boolean parallel = true;
	private boolean addToPath;
	protected transient SipURI pathURI;
	protected transient SipURI recordRouteURI;
	private transient SipURI outboundInterface;
	private transient SipFactoryImpl sipFactoryImpl;
	private boolean isNoCancel;
	
	transient HashMap<String, Object> transactionMap = new HashMap<String, Object>();
	
	private transient Map<URI, ProxyBranchImpl> proxyBranches;
	private boolean started; 
	private boolean ackReceived = false;
	private boolean tryingSent = false;
	// This branch is the final branch (set when the final response has been sent upstream by the proxy) 
	// that will be used for proxying subsequent requests
	private ProxyBranchImpl finalBranchForSubsequentRequests;
	
	// Keep the URI of the previous SIP entity that sent the original request to us (either another proxy or UA)
	private SipURI previousNode;
	
	// The From-header of the initiator of the request. Used to determine the direction of the request.
	// Caller -> Callee or Caller <- Callee
	private String callerFromHeader;
	// Issue 1791 : using a timer service created outside the application loader to avoid leaks on startup/shutdown
	private transient ProxyTimerService proxyTimerService;

	// empty constructor used only for Externalizable interface
	public ProxyImpl() {}
	
	public ProxyImpl(SipServletRequestImpl request, SipFactoryImpl sipFactoryImpl)
	{
		this.proxyTimerService = ((MobicentsSipApplicationSession)request.getApplicationSession(false)).getSipContext().getProxyTimerService();
		this.originalRequest = request;
		this.sipFactoryImpl = sipFactoryImpl;
		this.proxyBranches = new LinkedHashMap<URI, ProxyBranchImpl> ();		
		this.proxyTimeout = 180; // 180 secs default
		this.proxy1xxTimeout = -1; // not activated by default
		String outboundInterfaceStringified = ((MobicentsSipSession)request.getSession()).getOutboundInterface();
		if(outboundInterfaceStringified != null) {
			try {
				outboundInterface = (SipURI) sipFactoryImpl.createURI(outboundInterfaceStringified);
			} catch (ServletParseException e) {
				throw new IllegalArgumentException("couldn't parse the outbound interface " + outboundInterface, e);
			}
		}
		this.callerFromHeader = request.getFrom().toString();
		this.previousNode = extractPreviousNodeFromRequest(request);
		String txid = ((ViaHeader) request.getMessage().getHeader(ViaHeader.NAME)).getBranch();
		if(originalRequest.getTransactionApplicationData() != null) {
			this.transactionMap.put(txid, originalRequest.getTransactionApplicationData());
		}
	}
	
	/*
	 * This method will find the address of the machine that is the previous dialog path node.
	 * If there are proxies before the current one that are adding Record-Route we should visit them,
	 * otherwise just send to the client directly. And we don't want to visit proxies that are not
	 * Record-Routing, because they are not in the dialog path.
	 */
	private SipURI extractPreviousNodeFromRequest(SipServletRequestImpl request) {
		SipURI uri = null;
		try {
			// First check for record route
			RecordRouteHeader rrh = (RecordRouteHeader) request.getMessage().getHeader(RecordRouteHeader.NAME);
			if(rrh != null) {
				javax.sip.address.SipURI sipUri = (javax.sip.address.SipURI) rrh.getAddress().getURI();
				uri = new SipURIImpl(sipUri);
			} else { 
				// If no record route is found then use the last via (the originating endpoint)
				ListIterator<ViaHeader> viaHeaders = request.getMessage().getHeaders(ViaHeader.NAME);
				ViaHeader lastVia = null;
				while(viaHeaders.hasNext()) {
					lastVia = viaHeaders.next();
				} 
				String uriString = ((Via)lastVia).getSentBy().toString();
				uri = sipFactoryImpl.createSipURI(null, uriString);
				if(lastVia.getTransport() != null) {
					uri.setTransportParam(lastVia.getTransport());
				} else {
					uri.setTransportParam("udp");
				}
			}
		} catch (Exception e) {
			// We shouldn't completely fail in this case because it is rare to visit this code
			logger.error("Failed parsing previous address ", e);
		}
		return uri;

	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#cancel()
	 */
	public void cancel() {
		if(ackReceived) 
			throw new IllegalStateException("There has been an ACK received. Can not cancel more brnaches, the INVITE tx has finished.");
		cancelAllExcept(null, null, null, null, true);
	}
	
	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#cancel(java.lang.String[], int[], java.lang.String[])
	 */
	public void cancel(String[] protocol, int[] reasonCode, String[] reasonText) {
		if(ackReceived) 
			throw new IllegalStateException("There has been an ACK received. Can not cancel more brnaches, the INVITE tx has finished.");
		cancelAllExcept(null, protocol, reasonCode, reasonText, true);
	}

	public void cancelAllExcept(ProxyBranch except, String[] protocol, int[] reasonCode, String[] reasonText, boolean throwExceptionIfCannotCancel) {
		for(ProxyBranch proxyBranch : proxyBranches.values()) {		
			if(!proxyBranch.equals(except)) {
				// Do not make this check in the beginning of the method, because in case of reINVITE etc, we already have a single brnch nd this method
				// would have no actual effect, no need to fail it just because we've already seen ACK. Only throw exception if there are other branches.
				try {
					proxyBranch.cancel(protocol, reasonCode, reasonText);
				} catch (IllegalStateException e) {
					// TODO: Instead of catching excpetions here just determine if the branch is cancellable
					if(throwExceptionIfCannotCancel) throw e;
				}
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#createProxyBranches(java.util.List)
	 */
	public List<ProxyBranch> createProxyBranches(List<? extends URI> targets) {
		ArrayList<ProxyBranch> list = new ArrayList<ProxyBranch>();
		for(URI target: targets)
		{
			if(target == null) {
				throw new NullPointerException("URI can't be null");
			}
			if(!JainSipUtils.checkScheme(target.toString())) {
				throw new IllegalArgumentException("Scheme " + target.getScheme() + " is not supported");
			}
			ProxyBranchImpl branch = new ProxyBranchImpl(target, this);
			branch.setRecordRoute(recordRoutingEnabled);
			branch.setRecurse(recurse);
			list.add(branch);
			this.proxyBranches.put(target, branch);
		}
		return list;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getAddToPath()
	 */
	public boolean getAddToPath() {
		return addToPath;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getOriginalRequest()
	 */
	public SipServletRequest getOriginalRequest() {
		return originalRequest;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getParallel()
	 */
	public boolean getParallel() {
		return this.parallel;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getPathURI()
	 */
	public SipURI getPathURI() {
		if(!this.addToPath) throw new IllegalStateException("You must setAddToPath(true) before getting URI");
		return this.pathURI;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getProxyBranch(javax.servlet.sip.URI)
	 */
	public ProxyBranch getProxyBranch(URI uri) {
		return this.proxyBranches.get(uri);
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getProxyBranches()
	 */
	public List<ProxyBranch> getProxyBranches() {
		return new ArrayList<ProxyBranch>(this.proxyBranches.values());
	}
		
	public Map<URI, ProxyBranchImpl> getProxyBranchesMap() {
		return this.proxyBranches;
	}

	/**
	 * @return the finalBranchForSubsequentRequest
	 */
	public ProxyBranchImpl getFinalBranchForSubsequentRequests() {
		return finalBranchForSubsequentRequests;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getProxyTimeout()
	 */
	public int getProxyTimeout() {
		return this.proxyTimeout;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getRecordRoute()
	 */
	public boolean getRecordRoute() {
		return this.recordRoutingEnabled;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getRecordRouteURI()
	 */
	public SipURI getRecordRouteURI() {
		if(!this.recordRoutingEnabled) throw new IllegalStateException("You must setRecordRoute(true) before getting URI");
		return this.recordRouteURI;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getRecurse()
	 */
	public boolean getRecurse() {
		return this.recurse;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getSequentialSearchTimeout()
	 */
	public int getSequentialSearchTimeout() {
		return this.seqSearchTimeout;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getStateful()
	 */
	public boolean getStateful() {
		return true;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#getSupervised()
	 */
	public boolean getSupervised() {
		return this.supervised;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#proxyTo(java.util.List)
	 */
	public void proxyTo(final List<? extends URI> uris) {
		for (URI uri : uris)
		{
			if(uri == null) {
				throw new NullPointerException("URI can't be null");
			}
			if(!JainSipUtils.checkScheme(uri.toString())) {
				throw new IllegalArgumentException("Scheme " + uri.getScheme() + " is not supported");
			}
			final ProxyBranchImpl branch = new ProxyBranchImpl((SipURI) uri, this);
			branch.setRecordRoute(recordRoutingEnabled);
			branch.setRecurse(recurse);
			this.proxyBranches.put(uri, branch);
		}
		startProxy();
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#proxyTo(javax.servlet.sip.URI)
	 */
	public void proxyTo(final URI uri) {
		if(uri == null) {
			throw new NullPointerException("URI can't be null");
		}
		if(!JainSipUtils.checkScheme(uri.toString())) {
			throw new IllegalArgumentException("Scheme " + uri.getScheme() + " is not supported");
		}
		final ProxyBranchImpl branch = new ProxyBranchImpl(uri, this);
		branch.setRecordRoute(recordRoutingEnabled);
		branch.setRecurse(recurse);
		this.proxyBranches.put(uri, branch);
		startProxy();

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setAddToPath(boolean)
	 */
	public void setAddToPath(boolean p) {
		if(started) {
			throw new IllegalStateException("Cannot set a record route on an already started proxy");
		}
		if(this.pathURI == null) {
			this.pathURI = new SipURIImpl ( JainSipUtils.createRecordRouteURI( sipFactoryImpl.getSipNetworkInterfaceManager(), null));
		}		
		addToPath = p;

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setParallel(boolean)
	 */
	public void setParallel(boolean parallel) {
		this.parallel = parallel;

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setProxyTimeout(int)
	 */
	public void setProxyTimeout(int seconds) {
		if(seconds<=0) throw new IllegalArgumentException("Negative or zero timeout not allowed");
		
		proxyTimeout = seconds;
		for(ProxyBranchImpl proxyBranch : proxyBranches.values()) {	
			final boolean inactive = proxyBranch.isCanceled() || proxyBranch.isTimedOut();
			
			if(!inactive) {
				proxyBranch.setProxyBranchTimeout(seconds);
			}
		}

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setRecordRoute(boolean)
	 */
	public void setRecordRoute(boolean rr) {
		if(started) {
			throw new IllegalStateException("Cannot set a record route on an already started proxy");
		}
		if(rr) {
			Message message = null;
			if(originalRequest != null) {
				message = originalRequest.getMessage();
			}
			// record route should be based on the original received message
			this.recordRouteURI = new SipURIImpl ( JainSipUtils.createRecordRouteURI( sipFactoryImpl.getSipNetworkInterfaceManager(), message));
			if(logger.isDebugEnabled()) {
				logger.debug("Record routing enabled for proxy, Record Route used will be : " + recordRouteURI.toString());
			}
		}		
		this.recordRoutingEnabled = rr;

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setRecurse(boolean)
	 */
	public void setRecurse(boolean recurse) {
		this.recurse = recurse;

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setSequentialSearchTimeout(int)
	 */
	public void setSequentialSearchTimeout(int seconds) {
		seqSearchTimeout = seconds;

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setStateful(boolean)
	 */
	public void setStateful(boolean stateful) {
		//NOTHING

	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setSupervised(boolean)
	 */
	public void setSupervised(boolean supervised) {
		this.supervised = supervised;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#startProxy()
	 */
	public void startProxy() {
		if(this.ackReceived) 
			throw new IllegalStateException("Can't start. ACK has been received.");
		if(!this.originalRequest.isInitial())
			throw new IllegalStateException("Applications should not attepmt to " +
					"proxy subsequent requests. Proxying the initial request is " +
					"sufficient to carry all subsequent requests through the same" +
					" path.");

		// Only send TRYING when the request is INVITE, needed by testProxyGen2xx form TCK (it sends MESSAGE)
		if(this.originalRequest.getMethod().equals(Request.INVITE) && !tryingSent) {
			// Send provisional TRYING. Chapter 10.2
			// We must send only one TRYING no matter how many branches we spawn later.
			// This is needed for tests like testProxyBranchRecurse
			tryingSent = true;
			logger.info("Sending 100 Trying to the source");
			SipServletResponse trying =
				originalRequest.createResponse(100);			
			try {
				trying.send();
			} catch (IOException e) { 
				logger.error("Cannot send the 100 Trying",e);
			}
		}
		
		
		started = true;
		if(this.parallel) {
			for (final ProxyBranchImpl pb : this.proxyBranches.values()) {
				if(!pb.isStarted()) {
					pb.start();
				}
			}
		} else {
			startNextUntriedBranch();
		}		
	}
	
	public SipURI getOutboundInterface() {
		return outboundInterface;
	}
	
	public void onFinalResponse(ProxyBranchImpl branch) {
		//Get the final response
		final SipServletResponseImpl response = (SipServletResponseImpl) branch.getResponse();
		final int status = response.getStatus(); 
		
		// Cancel all others if 2xx or 6xx 10.2.4 and it's not a retransmission
		if(!isNoCancel && response.getTransaction() != null) {
			if(this.getParallel()) {
				if( (status >= 200 && status < 300) 
					|| (status >= 600 && status < 700) ) { 
					if(logger.isDebugEnabled())
						logger.debug("Cancelling all other broanches in this proxy");
					cancelAllExcept(branch, null, null, null, false);
				}
			}
		}
		// Recurse if allowed
		if(status >= 300 && status < 400
				&& recurse)
		{
			// We may want to store these for "moved permanently" and others
			ListIterator<Header> headers = 
				response.getMessage().getHeaders(ContactHeader.NAME);
			while(headers.hasNext()) {
				final ContactHeader contactHeader = (ContactHeader) headers.next();
				final javax.sip.address.URI addressURI = contactHeader.getAddress().getURI();
				URI contactURI = null;
				if (addressURI instanceof javax.sip.address.SipURI) {
					contactURI = new SipURIImpl(
							(javax.sip.address.SipURI) addressURI);
				} else if (addressURI instanceof javax.sip.address.TelURL) {
					contactURI = new TelURLImpl(
							(javax.sip.address.TelURL) addressURI);

				}
				final ProxyBranchImpl recurseBranch = new ProxyBranchImpl(contactURI, this);
				recurseBranch.setRecordRoute(recordRoutingEnabled);
				recurseBranch.setRecurse(recurse);
				this.proxyBranches.put(contactURI, recurseBranch);
				branch.addRecursedBranch(branch);
				if(parallel) {
					recurseBranch.start();
				}
				// if not parallel, just adding it to the list is enough
			}
		}
		
		// Sort best do far				
		if(bestResponse == null || bestResponse.getStatus() > status)
		{
			//Assume 600 and 400 are equally bad, the better one is the one that came first (TCK doBranchBranchTest)
			if(bestResponse != null) {
				if(status < 400) {
					bestResponse = response;
					bestBranch = branch;
				}
			} else {
				bestResponse = response;
				bestBranch = branch;
			}
		}
		
		if(logger.isDebugEnabled())
					logger.debug("Best response so far is " + bestResponse);
		
		// Check if we are waiting for more response
		if(parallel && allResponsesHaveArrived()) {
			finalBranchForSubsequentRequests = bestBranch;
			if(logger.isDebugEnabled())
					logger.debug("All responses have arrived, sending final response for parallel proxy" );
			sendFinalResponse(bestResponse, bestBranch);
		} else if (!parallel) {
			final int bestResponseStatus = bestResponse.getStatus();
			if(bestResponseStatus >= 200 && bestResponseStatus < 300) {
				finalBranchForSubsequentRequests = bestBranch;
				if(logger.isDebugEnabled())
					logger.debug("Sending final response for sequential proxy" );
				sendFinalResponse(bestResponse, bestBranch);
			} else {
				if(allResponsesHaveArrived()) {
					if(logger.isDebugEnabled())
						logger.debug("All responses have arrived for sequential proxy and we are sending the best one");
					sendFinalResponse(bestResponse, bestBranch);
				} else {
					if(logger.isDebugEnabled())
						logger.debug("Trying new branch in proxy" );
					startNextUntriedBranch();
					branch.onBranchTerminated();
				}
			}
		}

	}
	
	public void onBranchTimeOut(ProxyBranchImpl branch)
	{
		if(this.bestBranch == null) this.bestBranch = branch;
		if(allResponsesHaveArrived())
		{
			sendFinalResponse(bestResponse, bestBranch);
		}
		else
		{
			if(!parallel)
			{
				branch.cancel();
				startNextUntriedBranch();
				branch.onBranchTerminated();
			}
		}
	}
	
	// In sequential proxying get some untried branch and start it, then wait for response and repeat
	public void startNextUntriedBranch()
	{
		if(this.parallel) 
			throw new IllegalStateException("This method is only for sequantial proxying");
		
		for(final ProxyBranchImpl pbi: this.proxyBranches.values())
		{			
			if(!pbi.isStarted())
			{
				pbi.start();
				return;
			}
		}
	}
	
	public boolean allResponsesHaveArrived()
	{
		for(final ProxyBranchImpl pbi: this.proxyBranches.values())
		{
			final SipServletResponse response = pbi.getResponse();
			
			// The unstarted branches still haven't got a chance to get response
			if(!pbi.isStarted()) { 
				return false;
			}
			
			if(pbi.isStarted() && !pbi.isTimedOut() && !pbi.isCanceled())
			{
				if(response == null || 						// if there is no response yet
					response.getStatus() < Response.OK) {	// or if the response if not final
					return false;							// then we should wait more
				}
			}
		}
		return true;
	}
	
	public void sendFinalResponse(SipServletResponseImpl response,
			ProxyBranchImpl proxyBranch)
	{
		// If we didn't get any response and only a timeout just return a timeout
		if(proxyBranch.isTimedOut()) {
			try {
				originalRequest.createResponse(Response.REQUEST_TIMEOUT).send();
				if(logger.isDebugEnabled())
					logger.debug("Proxy branch has timed out");
				return;
			} catch (IOException e) {
				throw new IllegalStateException("Failed to send a timeout response", e);
			}
		}
			
		if(logger.isDebugEnabled())
					logger.debug("Proxy branch has NOT timed out");

		//Otherwise proceed with proxying the response
		final SipServletResponseImpl proxiedResponse = 
			ProxyUtils.createProxiedResponse(response, proxyBranch);
		

		if(proxiedResponse == null || proxiedResponse.getMessage() == null) {
			if(logger.isDebugEnabled())
				logger.debug("Response was dropped because getProxyUtils().createProxiedResponse(response, proxyBranch) returned null");
			return;// drop out of order message
		}

		try {

			if(originalRequest != null && proxiedResponse.getRequest() != null) {
				
				// non retransmission case
				try {
					String branch = ((Via)proxiedResponse.getMessage().getHeader(Via.NAME)).getBranch();
					synchronized(proxyBranch.ongoingTransactions) {
						for(TransactionRequest tr : proxyBranch.ongoingTransactions) {

							if(tr.branchId.equals(branch)) {
								((SipServletResponseImpl)proxiedResponse).setTransaction(tr.request.getTransaction());
								((SipServletResponseImpl)proxiedResponse).setOriginalRequest(tr.request);
								break;
							}
						}
					}
					proxiedResponse.send();
					if(logger.isDebugEnabled())
						logger.debug("Sending out proxied final response with existing transaction");
					proxyBranches.clear();
					originalRequest = null;
					bestBranch = null;
					bestResponse = null;
				} catch (Exception e) {
					logger.error("A problem occured while proxying the final response", e);
				}
			} else {
				// retransmission case, RFC3261 specifies that the retrans should be proxied statelessly
				final Message message = proxiedResponse.getMessage();
				String transport = JainSipUtils.findTransport(message);
				SipProvider sipProvider = getSipFactoryImpl().getSipNetworkInterfaceManager().findMatchingListeningPoint(
						transport, false).getSipProvider();
				try {
					if(logger.isDebugEnabled())
						logger.debug("Sending out proxied final response retransmission " + proxiedResponse);
					sipProvider.sendResponse((Response)message);
				} catch (SipException e) {
					logger.error("A problem occured while proxying the final response retransmission", e);
				}
			}
		} finally {
			// This cleanup will eliminate this issues where a retrans leaves unclean branch http://code.google.com/p/mobicents/issues/detail?id=1986
			bestBranch = null;
			bestResponse = null;
		}
	}		

	/**
	 * @return the bestResponse
	 */
	public SipServletResponseImpl getBestResponse() {
		return bestResponse;
	}
	
	public void setOriginalRequest(SipServletRequestImpl originalRequest) {
		this.originalRequest = originalRequest;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean getNoCancel() {
		return isNoCancel;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setNoCancel(boolean isNoCancel) {
		this.isNoCancel = isNoCancel;
	}

	/**
	 * @return the sipFactoryImpl
	 */
	public SipFactoryImpl getSipFactoryImpl() {
		return sipFactoryImpl;
	}

	/**
	 * @param sipFactoryImpl the sipFactoryImpl to set
	 */
	public void setSipFactoryImpl(SipFactoryImpl sipFactoryImpl) {
		this.sipFactoryImpl = sipFactoryImpl;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setOutboundInterface(java.net.InetAddress)
	 */
	public void setOutboundInterface(InetAddress inetAddress) {
		if(inetAddress == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		String address = inetAddress.getHostAddress();
		List<SipURI> list = this.sipFactoryImpl.getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.toString().contains(address)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				inetAddress.getHostAddress() + " not found");
		
		outboundInterface = networkInterface;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.Proxy#setOutboundInterface(java.net.InetSocketAddress)
	 */
	public void setOutboundInterface(InetSocketAddress inetSocketAddress) {
		if(inetSocketAddress == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		String address = inetSocketAddress.getAddress().getHostAddress()
			+ ":" + inetSocketAddress.getPort();
		List<SipURI> list = this.sipFactoryImpl.getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.toString().contains(address)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				address + " not found");		
		
		outboundInterface = networkInterface;
	}	
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyExt#setOutboundInterface(javax.servlet.sip.SipURI)
	 */
	public void setOutboundInterface(SipURI outboundInterface) {
		if(outboundInterface == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		List<SipURI> list = this.sipFactoryImpl.getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.equals(outboundInterface)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				outboundInterface + " not found");		
		
		this.outboundInterface = networkInterface;
	}
	
	public void setAckReceived(boolean received) {
		this.ackReceived = received;
	}
	
	public boolean getAckReceived() {
		return this.ackReceived;
	}
	
	public SipURI getPreviousNode() {
		return previousNode;
	}

	public String getCallerFromHeader() {
		return callerFromHeader;
	}

	public void setCallerFromHeader(String initiatorFromHeader) {
		this.callerFromHeader = initiatorFromHeader;
	}

	public HashMap<String, Object> getTransactionMap() {
		return transactionMap;
	}

	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		originalRequest = (SipServletRequestImpl) in.readObject();
		recurse = in.readBoolean();
		proxyTimeout = in.readInt();
		seqSearchTimeout = in.readInt();
		supervised = in.readBoolean();
		recordRoutingEnabled = in.readBoolean();
		parallel = in.readBoolean();
		addToPath = in.readBoolean();
		isNoCancel = in.readBoolean();
		started = in.readBoolean();
		ackReceived = in.readBoolean();
		tryingSent = in.readBoolean();
		finalBranchForSubsequentRequests = (ProxyBranchImpl) in.readObject();
		if(finalBranchForSubsequentRequests != null) {
			finalBranchForSubsequentRequests.setProxy(this);
		}
		previousNode = (SipURI) in.readObject();
		callerFromHeader = in.readUTF();
		this.proxyBranches = new LinkedHashMap<URI, ProxyBranchImpl> ();
	}

	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeObject(originalRequest);
		out.writeBoolean(recurse);
		out.writeInt(proxyTimeout);
		out.writeInt(seqSearchTimeout);
		out.writeBoolean(supervised);
		out.writeBoolean(recordRoutingEnabled);
		out.writeBoolean(parallel);
		out.writeBoolean(addToPath);
		out.writeBoolean(isNoCancel);
		out.writeBoolean(started);
		out.writeBoolean(ackReceived);
		out.writeBoolean(tryingSent);
		out.writeObject(finalBranchForSubsequentRequests);
		out.writeObject(previousNode);
		out.writeUTF(callerFromHeader);
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyExt#getProxy1xxTimeout()
	 */
	public int getProxy1xxTimeout() {
		return proxy1xxTimeout;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyExt#setProxy1xxTimeout(int)
	 */
	public void setProxy1xxTimeout(int timeout) {
		proxy1xxTimeout = timeout;
		
	}
	/**
	 * @return the proxyTimerService
	 */
	public ProxyTimerService getProxyTimerService() {
		return proxyTimerService;
	}

	public void addProxyBranch(ProxyBranchImpl proxyBranchImpl) {
		if(proxyBranches == null) {
			this.proxyBranches = new LinkedHashMap<URI, ProxyBranchImpl> ();
		}
		this.proxyBranches.put(proxyBranchImpl.getTargetURI(), proxyBranchImpl);
	}

	

}
