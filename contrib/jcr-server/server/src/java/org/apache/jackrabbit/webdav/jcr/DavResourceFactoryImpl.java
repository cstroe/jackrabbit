/*
 * Copyright 2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.webdav.jcr;

import org.apache.log4j.Logger;
import org.apache.jackrabbit.webdav.*;
import org.apache.jackrabbit.webdav.transaction.TransactionResource;
import org.apache.jackrabbit.webdav.transaction.TransactionDavServletRequest;
import org.apache.jackrabbit.webdav.observation.SubscriptionManager;
import org.apache.jackrabbit.webdav.observation.ObservationResource;
import org.apache.jackrabbit.webdav.version.DeltaVServletRequest;
import org.apache.jackrabbit.webdav.version.VersionControlledResource;
import org.apache.jackrabbit.webdav.jcr.version.VersionItemCollection;
import org.apache.jackrabbit.webdav.jcr.version.VersionHistoryItemCollection;
import org.apache.jackrabbit.webdav.jcr.transaction.TxLockManagerImpl;

import javax.jcr.*;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;

/**
 * <code>DavResourceFactoryImpl</code>...
 */
public class DavResourceFactoryImpl implements DavResourceFactory {

    private static Logger log = Logger.getLogger(DavResourceFactoryImpl.class);

    private final TxLockManagerImpl txMgr;
    private final SubscriptionManager subsMgr;

    /**
     * Create a new <code>DavResourceFactoryImpl</code>.
     *
     * @param txMgr
     * @param subsMgr
     */
    public DavResourceFactoryImpl(TxLockManagerImpl txMgr, SubscriptionManager subsMgr) {
        this.txMgr = txMgr;
        this.subsMgr = subsMgr;
    }

    /**
     * Create a new <code>DavResource</code> from the specified locator and request
     * objects. Note, that in contrast to
     * {@link #createResource(DavResourceLocator, DavSession)} the locator may
     * point to a non-existing resource.
     * <p/>
     * If the request contains a {@link org.apache.jackrabbit.webdav.version.DeltaVServletRequest#getLabel()
     * Label header}, the resource is build from the indicated
     * {@link org.apache.jackrabbit.webdav.version.VersionResource version} instead.
     *
     * @param locator
     * @param request
     * @param response
     * @return
     * @see DavResourceFactory#createResource(org.apache.jackrabbit.webdav.DavResourceLocator, org.apache.jackrabbit.webdav.DavServletRequest, org.apache.jackrabbit.webdav.DavServletResponse)
     */
    public DavResource createResource(DavResourceLocator locator,
                                      DavServletRequest request,
                                      DavServletResponse response) throws DavException {

        DavSession session = request.getDavSession();
        DavResource resource;
        if (locator.isRootLocation()) {
            resource = new RootCollection(locator, session, this);
        } else {
            try {
                resource = createResourceForItem(locator, session);

                /* if the created resource is version-controlled and the request
                contains a Label header, the corresponding Version must be used
                instead.*/
                if (request instanceof DeltaVServletRequest && isVersionControlled(resource)) {
                    String labelHeader = ((DeltaVServletRequest)request).getLabel();
                    if (labelHeader != null && DavMethods.isMethodAffectedByLabel(request.getMethod())) {
                        Item item = getItem(session, locator);
                        Version v = ((Node)item).getVersionHistory().getVersionByLabel(labelHeader);
                        DavResourceLocator vloc = locator.getFactory().createResourceLocator(locator.getPrefix(), locator.getWorkspacePath(), v.getPath(), false);
                        resource =  new VersionItemCollection(vloc, session, this, v);
                    }
                }
            } catch (PathNotFoundException e) {
                /* item does not exist yet: create the default resources
                Note: MKCOL request forces a collection-resource even if there already
                exists a repository-property with the given path. the MKCOL will
                in that particular case fail with a 405 (method not allowed).*/
                if (DavMethods.getMethodCode(request.getMethod()) == DavMethods.DAV_MKCOL) {
                    resource = new VersionControlledItemCollection(locator, session, this, null);
                } else {
                    resource = new DefaultItemResource(locator, session, this, null);
                }
            } catch (RepositoryException e) {
                log.error("Failed to build resource from item '"+ locator.getResourcePath() + "'");
                throw new JcrDavException(e);
            }
        }

        ((TransactionResource)resource).init(txMgr, ((TransactionDavServletRequest)request).getTransactionId());
        ((ObservationResource)resource).init(subsMgr);
        return resource;
    }

    /**
     * Create a new <code>DavResource</code> from the given locator and session.
     *
     * @param locator
     * @param session
     * @return DavResource representing either a repository item or the {@link RootCollection}.
     * @throws DavException if the given locator does neither refer to a repository item
     * nor does represent the {@link org.apache.jackrabbit.webdav.DavResourceLocator#isRootLocation()
     * root location}.
     */
    public DavResource createResource(DavResourceLocator locator, DavSession session) throws DavException {
        DavResource resource;
        try {
            resource = createResourceForItem(locator, session);
        } catch (RepositoryException e) {
            log.info("Creating resource for non-existing repository item ...");
            if (locator.isRootLocation()) {
                resource =  new RootCollection(locator, session, this);
            } else {
		// todo: is this correct?
		resource = new VersionControlledItemCollection(locator, session, this, null);
            }
        }

        // todo: currently transactionId is set manually after creation > to be improved.
        resource.addLockManager(txMgr);
        ((ObservationResource)resource).init(subsMgr);
        return resource;
    }

    /**
     * Tries to retrieve the repository item defined by the locator's resource
     * path and build the corresponding WebDAV resource. The following distinction
     * is made between items: Version nodes, VersionHistory nodes, root node,
     * unspecified nodes and finally property items.
     *
     * @param locator
     * @param session
     * @return DavResource representing a repository item.
     * @throws RepositoryException if {@link Session#getItem(String)} fails.
     */
    private DavResource createResourceForItem(DavResourceLocator locator, DavSession session) throws RepositoryException {
        DavResource resource;
        Item item = getItem(session, locator);
        if (item.isNode()) {
            // create special resources for Version and VersionHistory
            if (item instanceof Version) {
                resource = new VersionItemCollection(locator, session, this, item);
            } else if (item instanceof VersionHistory) {
                resource = new VersionHistoryItemCollection(locator, session, this, item);
            } else if (ItemResourceConstants.ROOT_ITEM_PATH.equals(item.getPath())) {
                resource =  new RootItemCollection(locator, session, this, item);
            }  else{
                resource = new VersionControlledItemCollection(locator, session, this, item);
            }
        } else {
            resource = new DefaultItemResource(locator, session, this, item);
        }
        return resource;
    }

    private Item getItem(DavSession davSession, DavResourceLocator locator) throws PathNotFoundException, RepositoryException {
        Session s = davSession.getRepositorySession();
        return s.getItem(locator.getJcrPath());
    }

    /**
     * Returns true, if the specified resource is a {@link VersionControlledResource}
     * and has a version history.
     *
     * @param resource
     * @return true if the specified resource is version-controlled.
     */
    private boolean isVersionControlled(DavResource resource) {
        boolean vc = false;
        if (resource instanceof VersionControlledResource) {
            try {
                vc = ((VersionControlledResource)resource).getVersionHistory() != null;
            } catch (DavException e) {
                log.debug("Resource '" + resource.getHref() + "' is not version-controlled.");
            }
        }
        return vc;
    }
}