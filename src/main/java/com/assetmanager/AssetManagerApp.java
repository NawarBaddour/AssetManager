package com.assetmanager;

import com.assetmanager.core.AssetStore;
import com.assetmanager.core.Database;
import com.assetmanager.core.ThumbnailService;
import com.assetmanager.util.Images;
import com.assetmanager.util.Log;
import com.assetmanager.web.WebUi;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Serves the library over HTTP and opens it in a browser.
 *
 * ./run.sh serve and open a browser
 * ./run.sh --no-open serve without launching a browser
 * ./run.sh ~/assets index a folder at startup
 */
public final class AssetManagerApp {

    public static final String APP_NAME = "AssetManager";

    public static void main(String[] args) {
        List<Path> startupPaths = new ArrayList<>();
        boolean openBrowser = true;

        for (String a : args) {
            if ("--no-open".equals(a)) {
                openBrowser = false;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            } else if (!a.startsWith("-")) {
                startupPaths.add(Path.of(a));
            } else {
                System.err.println("Unknown option: " + a);
                usage();
                System.exit(2);
                return;
            }
        }

        Log.info(APP_NAME + " starting (java " + System.getProperty("java.version") + ")");
        Images.init();

        Path home = Path.of(System.getProperty("user.home"), ".assetmanager");
        Database db;
        try {
            db = Database.open(home.resolve("library.db"));
        } catch (Exception e) {
            Log.error("could not open the library database", e);
            System.err.println("Could not open the library database at " + home.resolve("library.db"));
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        final Database fdb = db;
        final AssetStore store = new AssetStore(fdb);

        final ThumbnailService thumbs;
        try {
            thumbs = new ThumbnailService(store, home.resolve("cache"));
        } catch (Exception e) {
            Log.error("could not open the cache directory", e);
            System.err.println("Could not open " + home.resolve("cache") + ": " + e.getMessage());
            fdb.close();
            System.exit(1);
            return;
        }
        final ThumbnailService fthumbs = thumbs;

        final WebUi web;
        try {
            web = WebUi.start(store, fthumbs);
        } catch (Exception e) {
            Log.error("could not start the web server", e);
            System.err.println("Could not start the web server: " + e.getMessage());
            fthumbs.close();
            fdb.close();
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            fthumbs.close();
            fdb.close();
        }));

        String url = web.url();
        System.out.println();
        System.out.println("  " + APP_NAME + " is running at  " + url);
        System.out.println("  (the server only listens on 127.0.0.1; nothing leaves this machine)");
        System.out.println("  Ctrl+C in this terminal to stop.");
        System.out.println();

        if (!startupPaths.isEmpty())
            web.importPaths(startupPaths);
        if (openBrowser)
            openInBrowser(url);
    }

    private static void usage() {
        System.out.println();
        System.out.println("  " + APP_NAME + "  -  asset browser and manager");
        System.out.println();
        System.out.println("  ./run.sh [--no-open] [path ...]");
        System.out.println();
        System.out.println("    --no-open    serve the UI but do not launch a browser");
        System.out.println("    path ...     folder(s) to index at startup, or individual files");
        System.out.println();
    }

    private static void openInBrowser(String url) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
            } catch (Exception e) {
                Log.debug("Desktop.browse failed: " + e.getMessage());
            }
            for (String[] cmd : new String[][] {
                    { "xdg-open", url }, { "gio", "open", url }, { "gnome-open", url }, { "sensible-browser", url } }) {
                try {
                    new ProcessBuilder(cmd).start();
                    return;
                } catch (Exception ignored) {
                    /* try the next one */ }
            }
        }, "open-browser").start();
    }
}
