/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.core.security.user;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.QueryBuilder.RelationOp;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.spi.commons.iterator.Iterators;
import org.apache.jackrabbit.spi.commons.iterator.Predicate;
import org.apache.jackrabbit.spi.commons.iterator.Predicates;
import org.apache.jackrabbit.spi.commons.iterator.Transformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import java.util.Iterator;

/**
 * This evaluator for {@link org.apache.jackrabbit.api.security.user.Query}s use XPath
 * and some minimal client side filtering.  
 */
public class XPathQueryEvaluator implements XPathQueryBuilder.ConditionVisitor {
    static final Logger log = LoggerFactory.getLogger(XPathQueryEvaluator.class);

    private final XPathQueryBuilder builder;
    private final UserManagerImpl userManager;
    private final QueryManager queryManager;
    private final StringBuilder xPath = new StringBuilder();

    public XPathQueryEvaluator(XPathQueryBuilder builder, UserManagerImpl userManager, QueryManager queryManager) {
        this.builder = builder;
        this.userManager = userManager;
        this.queryManager = queryManager;
    }

    public Iterator<Authorizable> eval() throws RepositoryException {
        xPath.append("//element(*,")
             .append(builder.getSelector().getNtName())
             .append(')');

        XPathQueryBuilder.Condition condition = builder.getCondition();
        if (condition != null) {
            xPath.append('[');
            condition.accept(this);
            xPath.append(']');
        }

        String sortCol = builder.getSortProperty();
        if (sortCol != null) {
            xPath.append(" order by ")
                 .append(sortCol)
                 .append(' ')
                 .append(builder.getSortDirection().getDirection());
        }

        Query query = queryManager.createQuery(xPath.toString(), Query.XPATH);
        int count = builder.getMaxCount();
        if (count == 0) {
            return Iterators.empty();
        }

        if (count > 0) {
            query.setLimit(count);
        }

        return filter(toAuthorizables(execute(query)), builder.getGroupName(), builder.isDeclaredMembersOnly());
    }

    //------------------------------------------< ConditionVisitor >---

    public void visit(XPathQueryBuilder.PropertyCondition condition) throws RepositoryException {
        RelationOp relOp = condition.getOp();
        if (relOp == RelationOp.EX) {
            xPath.append(condition.getRelPath());
        }
        else {
            xPath.append(condition.getRelPath())    
                 .append(condition.getOp().getOp())
                 .append(format(condition.getValue()));
        }
    }

    public void visit(XPathQueryBuilder.ContainsCondition condition) {
        xPath.append("jcr:contains(")
             .append(condition.getRelPath())     
             .append(",'")
             .append(condition.getSearchExpr())
             .append("')");
    }

    public void visit(XPathQueryBuilder.ImpersonationCondition condition) {
        xPath.append("@rep:impersonators='")
             .append(condition.getName())
             .append('\'');
    }

    public void visit(XPathQueryBuilder.NotCondition condition) throws RepositoryException {
        xPath.append("not(");
        condition.getCondition().accept(this);
        xPath.append(')');
    }

    public void visit(XPathQueryBuilder.AndCondition condition) throws RepositoryException {
        int count = 0;
        for (XPathQueryBuilder.Condition c : condition) {
            xPath.append(count++ > 0 ? " and " : "");
            c.accept(this);
        }
    }

    public void visit(XPathQueryBuilder.OrCondition condition) throws RepositoryException {
        int pos = xPath.length();

        int count = 0;
        for (XPathQueryBuilder.Condition c : condition) {
            xPath.append(count++ > 0 ? " or " : "");
            c.accept(this);
        }

        // Surround or clause with parentheses if it contains more than one term
        if (count > 1) {
            xPath.insert(pos, '(');
            xPath.append(')');
        }
    }

    //------------------------------------------< private >---

    private static String format(Value value) throws RepositoryException {
        switch (value.getType()) {
            case PropertyType.STRING:
            case PropertyType.BOOLEAN:
                return '\'' + value.getString() + '\'';

            case PropertyType.LONG:
            case PropertyType.DOUBLE:
                return value.getString();

            case PropertyType.DATE:
                return "xs:dateTime('" + value.getString() + "')";

            default:
                throw new RepositoryException("Property of type " + PropertyType.nameFromValue(value.getType()) +
                        " not supported");
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Node> execute(Query query) throws RepositoryException {
        return query.execute().getNodes();
    }

    private Iterator<Authorizable> toAuthorizables(Iterator<Node> nodes) {
        Transformer<Node, Authorizable> transformer = new Transformer<Node, Authorizable>() {
            public Authorizable transform(Node node) {
                try {
                    return userManager.getAuthorizable((NodeImpl) node);
                } catch (RepositoryException e) {
                    log.warn("Cannot create authorizable from node {}", node);
                    log.debug(e.getMessage(), e);
                    return null;
                }
            }
        };

        return Iterators.transformIterator(nodes, transformer);
    }

    private Iterator<Authorizable> filter(Iterator<Authorizable> authorizables, String groupName,
                                          boolean declaredMembersOnly) throws RepositoryException {

        Predicate<Authorizable> predicate;
        if (groupName == null) {
            predicate = Predicates.TRUE();
        }
        else {
            Authorizable groupAuth = userManager.getAuthorizable(groupName);
            if (groupAuth == null || !groupAuth.isGroup()) {
                predicate = Predicates.FALSE();
            }
            else {
                final Group group = (Group) groupAuth;
                if (declaredMembersOnly) {
                    predicate = new Predicate<Authorizable>() {
                        public boolean evaluate(Authorizable authorizable) {
                            try {
                                return authorizable != null && group.isDeclaredMember(authorizable);
                            } catch (RepositoryException e) {
                                log.warn("Cannot determine whether {} is member of group {}", authorizable, group);
                                log.debug(e.getMessage(), e);
                                return false;
                            }
                        }
                    };

                }
                else {
                    predicate = new Predicate<Authorizable>() {
                        public boolean evaluate(Authorizable authorizable) {
                            try {
                                return authorizable != null && group.isMember(authorizable);
                            } catch (RepositoryException e) {
                                log.warn("Cannot determine whether {} is member of group {}", authorizable, group);
                                log.debug(e.getMessage(), e);
                                return false;
                            }
                        }
                    };
                }
            }
        }

        return Iterators.filterIterator(authorizables, predicate);
    }

}
