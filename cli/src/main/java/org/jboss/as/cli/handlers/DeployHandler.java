/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.handlers;

import java.io.File;
import java.io.FileInputStream;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.ParsedArguments;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.operation.OperationFormatException;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestBuilder;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class DeployHandler extends BatchModeCommandHandler {

    private final ArgumentWithoutValue force;
    private final ArgumentWithoutValue l;
    private final ArgumentWithoutValue path;
    private final ArgumentWithoutValue name;
    private final ArgumentWithoutValue rtName;

    public DeployHandler() {
        super("deploy", true);

        SimpleArgumentTabCompleter argsCompleter = (SimpleArgumentTabCompleter) this.getArgumentCompleter();

        l = new ArgumentWithoutValue("-l");
        l.setExclusive(true);
        argsCompleter.addArgument(l);

        path = new ArgumentWithValue(true, FilenameTabCompleter.INSTANCE, 0, "--path");
        path.addCantAppearAfter(l);
        argsCompleter.addArgument(path);

        force = new ArgumentWithoutValue("--force", "-f");
        force.addRequiredPreceding(path);
        argsCompleter.addArgument(force);

        name = new ArgumentWithValue("--name");
        name.addRequiredPreceding(path);
        argsCompleter.addArgument(name);

        rtName = new ArgumentWithValue("--runtime-name");
        rtName.addRequiredPreceding(path);
        argsCompleter.addArgument(rtName);
    }

    @Override
    protected void doHandle(CommandContext ctx) {

        ModelControllerClient client = ctx.getModelControllerClient();

        ParsedArguments args = ctx.getParsedArguments();
        boolean l = this.l.isPresent(args);
        if (!args.hasArguments() || l) {
            printList(ctx, Util.getDeployments(client), l);
            return;
        }

        final String path;
        try {
            path = this.path.getValue(args);
        } catch(IllegalArgumentException e) {
            ctx.printLine("The path argument is missing");
            return;
        }
        File f = new File(path);
        if(!f.exists()) {
            ctx.printLine("Path " + f.getAbsolutePath() + " doesn't exist.");
            return;
        }

        String name = this.name.getValue(args);
        if(name == null) {
            name = f.getName();
        }

        String runtimeName = rtName.getValue(args);

        if(Util.isDeployed(name, client)) {
            if(force.isPresent(args)) {
                DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();

                ModelNode result;

                // replace
                builder = new DefaultOperationRequestBuilder();
                builder.setOperationName("full-replace-deployment");
                builder.addProperty("name", name);
                if(runtimeName != null) {
                    builder.addProperty("runtime-name", runtimeName);
                }

                FileInputStream is = null;
                try {
                    is = new FileInputStream(f);
                    ModelNode request = builder.buildRequest();
                    OperationBuilder op = OperationBuilder.Factory.create(request);
                    op.addInputStream(is);
                    request.get("input-stream-index").set(0);
                    result = client.execute(op.build());
                } catch(Exception e) {
                    ctx.printLine("Failed to replace the deployment: " + e.getLocalizedMessage());
                    return;
                } finally {
                    StreamUtils.safeClose(is);
                }
                if(!Util.isSuccess(result)) {
                    ctx.printLine(Util.getFailureDescription(result));
                    return;
                }

                ctx.printLine("'" + name + "' re-deployed successfully.");
            } else {
                ctx.printLine("'" + name + "' is already deployed (use " + force.getDefaultName() + " to force re-deploy).");
            }

            return;
        } else {

            DefaultOperationRequestBuilder builder;

            ModelNode result;

            // add
            builder = new DefaultOperationRequestBuilder();
            builder.setOperationName("add");
            builder.addNode("deployment", name);
            if (runtimeName != null) {
                builder.addProperty("runtime-name", runtimeName);
            }

            FileInputStream is = null;
            try {
                is = new FileInputStream(f);
                ModelNode request = builder.buildRequest();
                OperationBuilder op = OperationBuilder.Factory.create(request);
                op.addInputStream(is);
                request.get("input-stream-index").set(0);
                result = client.execute(op.build());
            } catch (Exception e) {
                ctx.printLine("Failed to add the deployment content to the repository: "
                        + e.getLocalizedMessage());
                return;
            } finally {
                StreamUtils.safeClose(is);
            }
            if (!Util.isSuccess(result)) {
                ctx.printLine(Util.getFailureDescription(result));
                return;
            }

            // deploy
            builder = new DefaultOperationRequestBuilder();
            builder.setOperationName("deploy");
            builder.addNode("deployment", name);
            try {
                ModelNode request = builder.buildRequest();
                result = client.execute(request);
            } catch (Exception e) {
                ctx.printLine("Failed to deploy: " + e.getLocalizedMessage());
                return;
            }
            if (!Util.isSuccess(result)) {
                ctx.printLine(Util.getFailureDescription(result));
                return;
            }
            ctx.printLine("'" + name + "' deployed successfully.");
        }
    }

    public ModelNode buildRequest(CommandContext ctx) throws OperationFormatException {

        ParsedArguments args = ctx.getParsedArguments();
        if (!args.hasArguments()) {
            throw new OperationFormatException("Required arguments are missing.");
        }

        final String filePath;
        try {
            filePath = path.getValue(args);
        } catch(IllegalArgumentException e) {
            throw new OperationFormatException("Missing required path argument.");
        }
        String name = this.name.getValue(args);
        String runtimeName = rtName.getValue(args);


        File f = new File(filePath);
        if(!f.exists()) {
            throw new OperationFormatException(f.getAbsolutePath() + " doesn't exist.");
        }

        if(name == null) {
            name = f.getName();
        }

        if(Util.isDeployed(name, ctx.getModelControllerClient())) {
            if(args.hasArgument("f")) {
                DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();

                // replace
                builder = new DefaultOperationRequestBuilder();
                builder.setOperationName("full-replace-deployment");
                builder.addProperty("name", name);
                if(runtimeName != null) {
                    builder.addProperty("runtime-name", runtimeName);
                }

                byte[] bytes = readBytes(f);
                builder.getModelNode().get("bytes").set(bytes);
                return builder.buildRequest();
            } else {
                throw new OperationFormatException("'" + name + "' is already deployed (use -f to force re-deploy).");
            }
        }

        ModelNode composite = new ModelNode();
        composite.get("operation").set("composite");
        composite.get("address").setEmptyList();
        ModelNode steps = composite.get("steps");

        DefaultOperationRequestBuilder builder;

        // add
        builder = new DefaultOperationRequestBuilder();
        builder.setOperationName("add");
        builder.addNode("deployment", name);
        if (runtimeName != null) {
            builder.addProperty("runtime-name", runtimeName);
        }

        byte[] bytes = readBytes(f);
        builder.getModelNode().get("bytes").set(bytes);
        steps.add(builder.buildRequest());

        // deploy
        builder = new DefaultOperationRequestBuilder();
        builder.setOperationName("deploy");
        builder.addNode("deployment", name);
        steps.add(builder.buildRequest());

        return composite;
    }

    protected byte[] readBytes(File f) throws OperationFormatException {
        byte[] bytes;
        FileInputStream is = null;
        try {
            is = new FileInputStream(f);
            bytes = new byte[(int) f.length()];
            int read = is.read(bytes);
            if(read != bytes.length) {
                throw new OperationFormatException("Failed to read bytes from " + f.getAbsolutePath() + ": " + read + " from " + f.length());
            }
        } catch (Exception e) {
            throw new OperationFormatException("Failed to read file " + f.getAbsolutePath(), e);
        } finally {
            StreamUtils.safeClose(is);
        }
        return bytes;
    }
}
