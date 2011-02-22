package org.mobicents.servlet.sip.core.session;

import java.util.EventListener;

import javax.servlet.sip.SipServlet;

import org.apache.log4j.Logger;
import org.mobicents.servlet.sip.annotations.SipInstanceManager;
import org.mobicents.servlet.sip.startup.SipContext;
import org.mobicents.servlet.sip.startup.loading.SipServletImpl;

public class TomcatSipListenersHolder extends SipListenersHolder {

    private Logger logger = Logger.getLogger(TomcatSipListenersHolder.class);

    public TomcatSipListenersHolder(SipContext sipContext) {
        super(sipContext);
    }

    @Override
    public boolean loadListeners(String[] listeners, ClassLoader classLoader) {

        // Instantiate all the listeners
        for (String className : listeners) {
            try {
                Class listenerClass = Class.forName(className, false,
                        classLoader);
                EventListener listener = (EventListener) listenerClass
                        .newInstance();

                SipInstanceManager sipInstanceManager = ((SipContext) sipContext)
                        .getSipInstanceManager();
                sipInstanceManager.processAnnotations(listener,
                        sipInstanceManager.getInjectionMap(listenerClass
                                .getName()));

                SipServletImpl sipServletImpl = (SipServletImpl) sipContext
                        .findChildrenByClassName(className);
                if (sipServletImpl != null) {
                    listener = (EventListener) sipServletImpl.allocate();
                    listenerServlets.put(listener, sipServletImpl);
                } else {
                    SipServlet servlet = (SipServlet) listenerClass
                            .getAnnotation(SipServlet.class);
                    if (servlet != null) {
                        sipServletImpl = (SipServletImpl) sipContext
                                .findChildrenByName(servlet.getServletName());
                        if (sipServletImpl != null) {
                            listener = (EventListener) sipServletImpl
                                    .allocate();
                            listenerServlets.put(listener, sipServletImpl);
                        }
                    }
                }
                addListenerToBunch(listener);
            } catch (Exception e) {
                logger.fatal("Cannot instantiate listener class " + className,
                        e);
                return false;
            }
        }
        return true;

    }

}
