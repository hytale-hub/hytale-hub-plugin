package de.hytalehub.http;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.service.VoteDedupeService;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Integration with Nitrado:WebServer plugin (Mode 2: HTTP).
 * Registers HTTP endpoints when WebServer is available.
 */
public class WebServerIntegration {

    private static final PluginIdentifier WEBSERVER_IDENTIFIER =
            new PluginIdentifier("Nitrado", "WebServer");

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final VoteDedupeService dedupeService;

    private Object webServerPlugin;
    private VoteServlet voteServlet;
    private boolean registered;

    public WebServerIntegration(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            VoteHandler voteHandler,
            VoteDedupeService dedupeService) {
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.dedupeService = dedupeService;
        this.registered = false;
    }

    public boolean findWebServer() {
        try {
            PluginBase pluginInstance = PluginManager.get().getPlugin(WEBSERVER_IDENTIFIER);

            if (pluginInstance != null) {
                String className = pluginInstance.getClass().getName();
                if (className.equals("net.nitrado.hytale.plugins.webserver.WebServerPlugin")) {
                    this.webServerPlugin = pluginInstance;
                    plugin.log(Level.INFO, "Found Nitrado:WebServer plugin - HTTP vote mode available");
                    return true;
                }
            }

            plugin.log(Level.INFO, "Nitrado:WebServer not found - HTTP vote mode unavailable");
            return false;

        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error checking for WebServer plugin: " + e.getMessage());
            return false;
        }
    }

    public boolean register() {
        if (webServerPlugin == null) {
            plugin.log(Level.WARNING, "Cannot register HTTP endpoint - WebServer not available");
            return false;
        }

        if (registered) {
            plugin.log(Level.INFO, "HTTP vote endpoint already registered");
            return true;
        }

        try {
            // Create servlet instance
            voteServlet = new VoteServlet(plugin, config, voteHandler, dedupeService);

            // Get classes via reflection
            Class<?> httpServletClass = Class.forName("jakarta.servlet.http.HttpServlet");
            Class<?> filterClass = Class.forName("jakarta.servlet.Filter");
            Class<?> filterArrayClass = java.lang.reflect.Array.newInstance(filterClass, 0).getClass();

            Method addServletMethod = webServerPlugin.getClass().getMethod(
                    "addServlet",
                    PluginBase.class,
                    String.class,
                    httpServletClass,
                    filterArrayClass
            );

            Object emptyFilters = java.lang.reflect.Array.newInstance(filterClass, 0);

            addServletMethod.invoke(webServerPlugin, plugin, "/vote", voteServlet, emptyFilters);

            registered = true;
            plugin.log(Level.INFO, "Registered HTTP vote endpoint at /HytaleHub/HytaleHubPlugin/vote");
            return true;

        } catch (ClassNotFoundException e) {
            plugin.log(Level.SEVERE, "Jakarta Servlet API not available - WebServer plugin may be outdated");
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return false;
        } catch (NoSuchMethodException e) {
            plugin.log(Level.SEVERE, "WebServer plugin API mismatch - addServlet method not found");
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return false;
        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Failed to register HTTP vote endpoint: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    public void shutdown() {
        if (!registered || webServerPlugin == null) {
            return;
        }

        try {
            Method removeServletsMethod = webServerPlugin.getClass().getMethod(
                    "removeServlets",
                    PluginBase.class
            );

            removeServletsMethod.invoke(webServerPlugin, plugin);
            registered = false;
            plugin.log(Level.INFO, "Unregistered HTTP vote endpoint");

        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error unregistering HTTP endpoint: " + e.getMessage());
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean isWebServerAvailable() {
        return webServerPlugin != null;
    }

    public long getRequestCount() {
        return voteServlet != null ? voteServlet.getRequestCount() : 0;
    }
}
