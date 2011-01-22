/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.controller.operations.common;


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.common.PathDescription.RELATIVE_TO;

import java.util.Locale;

import org.jboss.as.controller.Cancellable;
import org.jboss.as.controller.ModelAddOperationHandler;
import org.jboss.as.controller.NewOperationContext;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ResultHandler;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.descriptions.common.PathDescription;
import org.jboss.as.controller.operations.validation.ModelTypeValidator;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Handler for the path resource add operation.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class PathAddHandler implements ModelAddOperationHandler, DescriptionProvider {

    public static final String OPERATION_NAME = ADD;

    public static ModelNode getAddPathOperation(ModelNode address, ModelNode path, ModelNode relativeTo) {
        ModelNode op = new ModelNode();
        op.get(OP).set(OPERATION_NAME);
        op.get(OP_ADDR).set(address);
        if (path.isDefined()) {
            op.get(PATH).set(path);
        }
        if (relativeTo.isDefined()) {
            op.get(RELATIVE_TO).set(relativeTo);
        }
        return op;
    }

    public static final PathAddHandler NAMED_INSTANCE = new PathAddHandler(false);

    public static final PathAddHandler SPECIFIED_INSTANCE = new PathAddHandler(true);

    private final boolean specified;
    private final StringLengthValidator pathValidator;
    private final ModelTypeValidator relativeToValidator = new ModelTypeValidator(ModelType.STRING, true);

    /**
     * Create the AbstractAddExtensionHandler
     */
    protected PathAddHandler(boolean specified) {
        this.specified = specified;
        this.pathValidator = new StringLengthValidator(1, !specified);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Cancellable execute(NewOperationContext context, ModelNode operation, ResultHandler resultHandler) {
        try {
            PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
            String name = address.getLastElement().getValue();
            ModelNode model = context.getSubModel();
            model.get(NAME).set(name);

            ModelNode pathNode = model.get(PATH);
            String failure = pathValidator.validateParameter(PATH, pathNode);
            if (failure == null) {
                String path = pathNode.isDefined() ? pathNode.asString() : null;
                ModelNode relNode = model.get(RELATIVE_TO);
                failure = relativeToValidator.validateParameter(RELATIVE_TO, relNode);
                if (failure == null) {
                    String relativeTo = relNode.isDefined() ? pathNode.asString() : null;
                    ModelNode compensating = PathRemoveHandler.getRemovePathOperation(operation.get(OP_ADDR));
                    installPath(name, path, relativeTo, context, resultHandler, compensating);
                }
            }
            if (failure != null) {
                resultHandler.handleFailed(new ModelNode().set(failure));
            }
        }
        catch (Exception e) {
            resultHandler.handleFailed(new ModelNode().set(e.getLocalizedMessage()));
        }
        return Cancellable.NULL;
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        return specified ? PathDescription.getSpecifiedPathDescription(locale) : PathDescription.getNamedPathAddOperation(locale);
    }

    protected void installPath(String name, String path, String relativeTo, NewOperationContext context, ResultHandler resultHandler, ModelNode compensatingOp) {
        resultHandler.handleResultComplete(compensatingOp);
    }

}