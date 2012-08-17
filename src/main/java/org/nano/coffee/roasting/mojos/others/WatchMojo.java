package org.nano.coffee.roasting.mojos.others;

import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.nano.coffee.roasting.mojos.AbstractRoastingCoffeeMojo;
import org.nano.coffee.roasting.processors.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This mojo watches the file change in the source directories and process them automatically.
 * To work correctly, launch <tt>mvn clean test</tt> first. This will resolve and prepare all required file.
 * Then <tt>mvn org.nano.coffee-roasting:coffee-roasting-maven-plugin:watch</tt> will starts the <i>watch</i> mode.
 * @goal watch
 */
public class WatchMojo extends AbstractRoastingCoffeeMojo implements FileListener {

    /**
     * @parameter default-value="true"
     */
    protected boolean watchCoffeeScript;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchLess;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchDoAggregate;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchValidateJS;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchValidateCSS;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchRunServer;

    /**
     * @parameter default-value="8234"
     */
    protected int watchJettyServerPort;

    /**
     * @parameter
     */
    List<String> javascriptAggregation;

    /**
     * @parameter
     */
    protected List<String> cssAggregation;

    /**
     * The Jetty Server
     */
    protected Server server;

    public String MESSAGE = "You're running the watch mode. All modified file will be processed " +
            "automatically. \n" +
            "If the jetty server is enabled, they will also be served from http://localhost:" +
            watchJettyServerPort + "/. \n" +
            "The jasmine runner is available from http://localhost:" + watchJettyServerPort + "/jasmine. \n" +
            "To leave the watch mode, just hit CTRL+C.\n";
    /**
     * The processors
     */
    protected HashMap<String, Processor> processors;


    public void execute() throws MojoExecutionException, MojoFailureException {
        buildProcessorsList();
        try {
            setupMonitor();
        } catch (FileSystemException e) {
            throw new MojoExecutionException("Cannot set the file monitor on the source folder", e);
        }

        getLog().info(MESSAGE);

        if (watchRunServer) {
            try {

                server = new Server();
                addConnectorToServer();
                addHandlersToServer();
                startServer();
            } catch (Exception e){
                throw new MojoExecutionException("Cannot run the jetty server", e);
            }
        } else {
            try {
                Thread.sleep(1000000000); // Pretty long
            } catch (InterruptedException e) { /* ignore */ }
        }
    }

    private void buildProcessorsList() {
        processors = new HashMap<String, Processor>();
        processors.put("coffeescript", new CoffeeScriptCompilationProcessor());
        processors.put("jscopy", new JavaScriptFileCopyProcessor());
        processors.put("jsaggregator", new JavaScriptAggregator());
        processors.put("cssaggregator", new CSSAggregator());

    }
    private void setupMonitor() throws FileSystemException {
        File src = new File(project.getBasedir(), "src");
        getLog().info("Set up file monitor on " + src);
        FileSystemManager fsManager = VFS.getManager();
        FileObject listendir = fsManager.resolveFile(src.getAbsolutePath());

        DefaultFileMonitor fm = new DefaultFileMonitor(this);
        fm.setRecursive(true);
        fm.addFile(listendir);
        fm.start();
    }

    private void addConnectorToServer() {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(watchJettyServerPort);
        server.addConnector(connector);
    }

    private void addHandlersToServer() {
        HandlerList list = new HandlerList();
        list.addHandler(new DirectoryHandler(getWorkDirectory()));
        list.addHandler(new DirectoryHandler(getLibDirectory()));
        list.addHandler(new DirectoryHandler(getWorkTestDirectory()));
        list.addHandler(new JasmineHandler(this));
        server.setHandler(list);
    }

    private void startServer() throws Exception {
        server.start();
        server.join();
    }

    public void fileCreated(FileChangeEvent event) throws Exception {
        getLog().info("New file found " + event.getFile().getName().getBaseName());

        if (event.getFile().getType() == FileType.FOLDER) {
            getLog().info(event.getFile().getName() + " is a directory");
        } else {
            processNewOrUpdatedFile(event.getFile());
        }
    }


    public void fileDeleted(FileChangeEvent event) throws Exception {
        getLog().info("File " + event.getFile().getName().getBaseName() + " deleted");

        if (event.getFile().getType() == FileType.FOLDER) {
            getLog().info(event.getFile().getName() + " is a directory");
        } else {
            processDeletedFile(event.getFile());
        }
    }

    private void processDeletedFile(FileObject file) {
        if (watchCoffeeScript  && file.getName().getExtension().equals("coffee")) {
            // Delete generated JS file
            String jsFileName = file.getName().getBaseName().substring(0, file.getName().getBaseName().length() - ".coffee".length
                    ())
                    + ".js";
            File out = new File(getWorkDirectory(), jsFileName);
            if (out.isFile()) {
                out.delete();
            }
            if (watchDoAggregate) {
                doJSAggregation();
            }
        }

        if (file.getName().getExtension().equals("js")) {
            File out = new File(getWorkDirectory(), file.getName().getBaseName());
            if (out.isFile()) {
                out.delete();
            }
            File out2 = new File(getWorkTestDirectory(), file.getName().getBaseName());
            if (out2.isFile()) {
                out2.delete();
            }
            if (watchDoAggregate) {
                doJSAggregation();
            }
        }
    }

    public void fileChanged(FileChangeEvent event) throws Exception {
        getLog().info("File changed: " + event.getFile().getName().getBaseName());

        if (event.getFile().getType() == FileType.FOLDER) {
            getLog().info(event.getFile().getName() + " is a directory");
        } else {
            processNewOrUpdatedFile(event.getFile());
        }
    }

    private void processNewOrUpdatedFile(FileObject file) {
        String path = file.getName().getPath();
        File theFile = new File(path);
        if (! theFile.isFile()) {
            getLog().error("Something terrible happen, the " + file.getName().getPath() + " is not a file...");
            return;
        }

        if (watchCoffeeScript  && file.getName().getExtension().equals("coffee")  && isMainFile(theFile)) {
            doCoffeeScriptCompilation(theFile);
            if (watchDoAggregate) {
                doJSAggregation();
            }
        }

        if (watchCoffeeScript  && file.getName().getExtension().equals("coffee")  && isTestFile(theFile)) {
            doTestCoffeeScriptCompilation(theFile);
        }

        if (file.getName().getExtension().equals("js")  && isMainFile(theFile)) {
            doMainJavaScriptCopy(theFile);
        }

        if (file.getName().getExtension().equals("js")  && isTestFile(theFile)) {
            doTestJavaScriptCopy(theFile);
        }

        if (watchDoAggregate  && file.getName().getExtension().equals("js")) {
            doJSAggregation();
        }

        if (watchDoAggregate  && file.getName().getExtension().equals("css")) {
            doCSSAggregation();
        }

    }

    private void doCoffeeScriptCompilation(File file) {
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", getWorkDirectory());
        try {
            processors.get("coffeescript").process(file, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("CoffeeScript compilation failed", e);
        }
    }

    private void doTestCoffeeScriptCompilation(File file) {
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", getWorkTestDirectory());
        try {
            processors.get("coffeescript").process(file, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("CoffeeScript compilation failed", e);
        }
    }

    private void doJSAggregation() {
        File output = new File(getWorkDirectory(), project.getBuild().getFinalName() + ".js");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", output);
        options.put("work", getWorkDirectory());
        options.put("names", javascriptAggregation);

        try {
            processors.get("jsaggregator").process(null, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("JavaScript aggregation failed", e);
        }
    }

    private void doCSSAggregation() {
        File output = new File(getWorkDirectory(), project.getBuild().getFinalName() + ".css");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", output);
        options.put("work", getWorkDirectory());
        options.put("names", cssAggregation);

        try {
            processors.get("cssaggregator").process(null, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("CSS aggregation failed", e);
        }
    }

    private void doMainJavaScriptCopy(File file) {
        File output = getWorkDirectory();
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", output);

        try {
            processors.get("jscopy").process(file, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("JavaScript copy failed", e);
        }
    }

    private void doTestJavaScriptCopy(File file) {
        File output = getWorkTestDirectory();
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("output", output);

        try {
            processors.get("jscopy").process(file, options);
        } catch (Processor.ProcessorException e) {
            getLog().error("JavaScript copy failed", e);
        }
    }

    private boolean isMainFile(File file) {
        return file.getAbsolutePath().contains("src/main");
    }

    private boolean isTestFile(File file) {
        return file.getAbsolutePath().contains("src/test");
    }


}