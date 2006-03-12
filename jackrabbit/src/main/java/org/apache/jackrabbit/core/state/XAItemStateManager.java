/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
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
package org.apache.jackrabbit.core.state;

import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.TransactionException;
import org.apache.jackrabbit.core.TransactionContext;
import org.apache.jackrabbit.core.InternalXAResource;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.observation.EventStateCollectionFactory;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.uuid.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ReferentialIntegrityException;
import javax.jcr.PropertyType;
import java.util.Iterator;

/**
 * Extension to <code>LocalItemStateManager</code> that remembers changes on
 * multiple save() requests and commits them only when an associated transaction
 * is itself committed.
 */
public class XAItemStateManager extends LocalItemStateManager implements InternalXAResource {

    /**
     * Logger instance.
     */
    private static Logger log = LoggerFactory.getLogger(XAItemStateManager.class);

    /**
     * Default change log attribute name.
     */
    private static final String DEFAULT_ATTRIBUTE_NAME = "ChangeLog";

    /**
     * ThreadLocal that holds the ChangeLog while this state manager is in one
     * of the {@link #prepare}, {@link #commit}, {@link #rollback}
     * methods.
     */
    private ThreadLocal commitLog = new ThreadLocal() {
        protected synchronized Object initialValue() {
            return new CommitLog();
        }
    };

    /**
     * Current instance-local change log.
     */
    private transient ChangeLog txLog;

    /**
     * Current update operation.
     */
    private transient SharedItemStateManager.Update update;

    /**
     * Change log attribute name.
     */
    private final String attributeName;

    /**
     * Optional virtual item state provider.
     */
    private VirtualItemStateProvider virtualProvider;

    /**
     * Creates a new instance of this class.
     *
     * @param sharedStateMgr shared state manager
     * @param factory        event state collection factory
     */
    public XAItemStateManager(SharedItemStateManager sharedStateMgr,
                              EventStateCollectionFactory factory) {
        this(sharedStateMgr, factory, DEFAULT_ATTRIBUTE_NAME);
    }

    /**
     * Creates a new instance of this class with a custom attribute name.
     *
     * @param sharedStateMgr shared state manager
     * @param factory        event state collection factory
     * @param attributeName  attribute name
     */
    public XAItemStateManager(SharedItemStateManager sharedStateMgr,
                              EventStateCollectionFactory factory,
                              String attributeName) {
        super(sharedStateMgr, factory);

        this.attributeName = attributeName;
    }

    /**
     * Set optional virtual item state provider.
     */
    public void setVirtualProvider(VirtualItemStateProvider virtualProvider) {
        this.virtualProvider = virtualProvider;
    }

    /**
     * {@inheritDoc}
     */
    public void associate(TransactionContext tx) {
        ChangeLog txLog = null;
        if (tx != null) {
            txLog = (ChangeLog) tx.getAttribute(attributeName);
            if (txLog == null) {
                txLog = new ChangeLog();
                tx.setAttribute(attributeName, txLog);
            }
        }
        this.txLog = txLog;
    }

    /**
     * {@inheritDoc}
     */
    public void beforeOperation(TransactionContext tx) {
        ChangeLog txLog = (ChangeLog) tx.getAttribute(attributeName);
        if (txLog != null) {
            ((CommitLog) commitLog.get()).setChanges(txLog);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void prepare(TransactionContext tx) throws TransactionException {
        ChangeLog txLog = (ChangeLog) tx.getAttribute(attributeName);
        if (txLog != null) {
            try {
                if (virtualProvider != null) {
                    updateVirtualReferences(txLog);
                }
                update = sharedStateMgr.beginUpdate(txLog, factory, virtualProvider);
            } catch (ReferentialIntegrityException rie) {
                log.error(rie.getMessage(), rie);
                txLog.undo(sharedStateMgr);
                throw new TransactionException("Unable to prepare transaction.", rie);
            } catch (ItemStateException ise) {
                log.error(ise.getMessage(), ise);
                txLog.undo(sharedStateMgr);
                throw new TransactionException("Unable to prepare transaction.", ise);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void commit(TransactionContext tx) throws TransactionException {
        ChangeLog txLog = (ChangeLog) tx.getAttribute(attributeName);
        if (txLog != null) {
            try {
                update.end();
            } catch (ItemStateException ise) {
                log.error(ise.getMessage(), ise);
                txLog.undo(sharedStateMgr);
                throw new TransactionException("Unable to commit transaction.", ise);
            }
            txLog.reset();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void rollback(TransactionContext tx) {
        ChangeLog txLog = (ChangeLog) tx.getAttribute(attributeName);
        if (txLog != null) {
            if (update != null) {
                update.cancel();
            }
            txLog.undo(sharedStateMgr);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void afterOperation(TransactionContext tx) {
        ((CommitLog) commitLog.get()).setChanges(null);
    }

    /**
     * Returns the current change log. First tries thread-local change log,
     * then instance-local change log. Returns <code>null</code> if no
     * change log was found.
     */
    public ChangeLog getChangeLog() {
        ChangeLog changeLog = ((CommitLog) commitLog.get()).getChanges();
        if (changeLog == null) {
            changeLog = txLog;
        }
        return changeLog;
    }

    //-----------------------------------------------------< ItemStateManager >
    /**
     * {@inheritDoc}
     * <p/>
     * If this state manager is committing changes, this method first checks
     * the commitLog ThreadLocal. Else if associated to a transaction check
     * the transactional change log. Fallback is always the call to the base
     * class.
     */
    public ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        if (virtualProvider != null && virtualProvider.hasItemState(id)) {
            return virtualProvider.getItemState(id);
        }
        ChangeLog changeLog = getChangeLog();
        if (changeLog != null) {
            ItemState state = changeLog.get(id);
            if (state != null) {
                return state;
            }
        }
        return super.getItemState(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If this state manager is committing changes, this method first checks
     * the commitLog ThreadLocal. Else if associated to a transaction check
     * the transactional change log. Fallback is always the call to the base
     * class.
     */
    public boolean hasItemState(ItemId id) {
        if (virtualProvider != null && virtualProvider.hasItemState(id)) {
            return true;
        }
        ChangeLog changeLog = getChangeLog();
        if (changeLog != null) {
            try {
                ItemState state = changeLog.get(id);
                if (state != null) {
                    return true;
                }
            } catch (NoSuchItemStateException e) {
                return false;
            }
        }
        return super.hasItemState(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If this state manager is committing changes, this method first
     * checks the commitLog ThreadLocal. Else if associated to a transaction
     * check the transactional change log. Fallback is always the call to
     * the base class.
     */
    public NodeReferences getNodeReferences(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {

        if (virtualProvider != null && virtualProvider.hasNodeReferences(id)) {
            return virtualProvider.getNodeReferences(id);
        }
        ChangeLog changeLog = getChangeLog();
        if (changeLog != null) {
            NodeReferences refs = changeLog.get(id);
            if (refs != null) {
                return refs;
            }
        }
        return super.getNodeReferences(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If this state manager is committing changes, this method first
     * checks the commitLog ThreadLocal. Else if associated to a transaction
     * check the transactional change log. Fallback is always the call to
     * the base class.
     */
    public boolean hasNodeReferences(NodeReferencesId id) {
        if (virtualProvider != null && virtualProvider.hasNodeReferences(id)) {
            return true;
        }
        ChangeLog changeLog = getChangeLog();
        if (changeLog != null) {
            if (changeLog.get(id) != null) {
                return true;
            }
        }
        return super.hasNodeReferences(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If associated with a transaction, simply merge the changes given to
     * the ones already known (removing items that were first added and
     * then again deleted).
     */
    protected void update(ChangeLog changeLog)
            throws ReferentialIntegrityException, StaleItemStateException,
            ItemStateException {
        if (txLog != null) {
            txLog.merge(changeLog);
        } else {
            super.update(changeLog);
        }
    }

    //-------------------------------------------------------< implementation >

    /**
     * Determine all node references whose targets only exist in the view of
     * this transaction and store the modified view back to the virtual provider.
     * @param changes change log
     * @throws ItemStateException if an error occurs
     */
    private void updateVirtualReferences(ChangeLog changes) throws ItemStateException {
        for (Iterator iter = changes.addedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState prop = (PropertyState) state;
                if (prop.getType() == PropertyType.REFERENCE) {
                    InternalValue[] vals = prop.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        UUID uuid = (UUID) vals[i].internalValue();
                        NodeReferencesId refsId = new NodeReferencesId(uuid);
                        addVirtualReference(prop.getPropertyId(), refsId);
                    }
                }
            }
        }
        for (Iterator iter = changes.modifiedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState newProp = (PropertyState) state;
                PropertyState oldProp =
                        (PropertyState) getItemState(state.getId());
                if (oldProp.getType() == PropertyType.REFERENCE) {
                    InternalValue[] vals = oldProp.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        UUID uuid = (UUID) vals[i].internalValue();
                        NodeReferencesId refsId = new NodeReferencesId(uuid);
                        removeVirtualReference(oldProp.getPropertyId(), refsId);
                    }
                }
                if (newProp.getType() == PropertyType.REFERENCE) {
                    InternalValue[] vals = newProp.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        UUID uuid = (UUID) vals[i].internalValue();
                        NodeReferencesId refsId = new NodeReferencesId(uuid);
                        addVirtualReference(newProp.getPropertyId(), refsId);
                    }
                }
            }
        }
        for (Iterator iter = changes.deletedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState prop = (PropertyState) state;
                if (prop.getType() == PropertyType.REFERENCE) {
                    InternalValue[] vals = prop.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        UUID uuid = (UUID) vals[i].internalValue();
                        NodeReferencesId refsId = new NodeReferencesId(uuid);
                        removeVirtualReference(prop.getPropertyId(), refsId);
                    }
                }
            }
        }
    }

    /**
     * Add a virtual reference from some reference property to a virtual node.
     * Ignored if <code>refsId.getTargetId()</code> does not denote a
     * virtual node.
     * @param sourceId property id
     * @param refsId node references id
     */
    private void addVirtualReference(PropertyId sourceId,
                                     NodeReferencesId refsId)
            throws NoSuchItemStateException, ItemStateException {

        NodeReferences refs = virtualProvider.getNodeReferences(refsId);
        if (refs == null && virtualProvider.hasItemState(refsId.getTargetId())) {
            refs = new NodeReferences(refsId);
        }
        if (refs != null) {
            refs.addReference(sourceId);
            virtualProvider.setNodeReferences(refs);
        }
    }

    /**
     * Remove a virtual reference from some reference property to a virtual node.
     * Ignored if <code>refsId.getTargetId()</code> does not denote a
     * virtual node.
     * @param sourceId property id
     * @param refsId node references id
     */
    private void removeVirtualReference(PropertyId sourceId,
                                        NodeReferencesId refsId)
            throws NoSuchItemStateException, ItemStateException {

        NodeReferences refs = virtualProvider.getNodeReferences(refsId);
        if (refs == null && virtualProvider.hasItemState(refsId.getTargetId())) {
            refs = new NodeReferences(refsId);
        }
        if (refs != null) {
            refs.removeReference(sourceId);
            virtualProvider.setNodeReferences(refs);
        }
    }

    //--------------------------< inner classes >-------------------------------

    /**
     * Helper class that serves as a container for a ChangeLog in a ThreadLocal.
     * The <code>CommitLog</code> is associated with a <code>ChangeLog</code>
     * while the <code>TransactionalItemStateManager</code> is in the commit
     * method.
     */
    private static class CommitLog {

        /**
         * The changes that are about to be committed
         */
        private ChangeLog changes;

        /**
         * Sets changes that are about to be committed.
         *
         * @param changes that are about to be committed, or <code>null</code>
         *                if changes have been committed and the commit log should be reset.
         */
        private void setChanges(ChangeLog changes) {
            this.changes = changes;
        }

        /**
         * The changes that are about to be committed, or <code>null</code> if
         * the <code>TransactionalItemStateManager</code> is currently not
         * committing any changes.
         *
         * @return the changes about to be committed.
         */
        private ChangeLog getChanges() {
            return changes;
        }
    }
}
