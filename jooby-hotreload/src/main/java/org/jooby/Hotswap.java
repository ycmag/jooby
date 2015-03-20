/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Start a target app with a custom classloader. The classloader is responsible for loading
 * resources from:
 *
 * 1. public and config directories (probably src/main/resources too)
 * 2. target/classes, current app (*.class)
 *
 * The parent classloader must load 1) and all the *.jars files.
 *
 * On changes ONLY the custom classloader (App classlaoder) is bounced it.
 *
 * @author edgar
 *
 */
public class Hotswap {

  private URLClassLoader loader;

  private volatile Object app;

  private File[] cp;

  private String mainClass;

  private Watcher scanner;

  private ExecutorService executor;

  private Path[] paths;

  private PathMatcher includes;

  private PathMatcher excludes;

  private boolean dcevm;

  private List<File> dirs;

  public Hotswap(final String mainClass, final File[] cp) throws IOException {
    this.mainClass = mainClass;
    this.cp = cp;
    this.dirs = new ArrayList<File>();
    for (File file : cp) {
      if (file.isDirectory()) {
        dirs.add(file);
      }
    }
    this.paths = toPath(dirs.toArray(new File[dirs.size()]));
    this.executor = Executors.newSingleThreadExecutor();
    this.scanner = new Watcher(this::onChange, paths);
    dcevm = System.getProperty("java.vm.version").toLowerCase().contains("dcevm");
  }

  public static void main(final String[] args) throws Exception {
    List<File> cp = new ArrayList<File>();
    String includes = "**/*.class,**/*.conf,**/*.properties";
    String excludes = "";
    for (int i = 1; i < args.length; i++) {
      File dir = new File(args[i]);
      if (dir.exists()) {
        // cp option
        cp.add(dir);
      } else {
        String[] option = args[i].split("=");
        if (option.length < 2) {
          throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
        String name = option[0].toLowerCase();
        switch (name) {
          case "includes":
            includes = option[1];
            break;
          case "excludes":
            excludes = option[1];
            break;
          default:
            throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
      }
    }
    if (cp.size() == 0) {
      String[] defcp = {"public", "config", "target/classes" };
      for (String candidate : defcp) {
        File dir = new File(candidate);
        if (dir.exists()) {
          cp.add(dir);
        }
      }
    }
    Hotswap launcher = new Hotswap(args[0], cp.toArray(new File[cp.size()]))
        .includes(includes)
        .excludes(excludes);
    launcher.run();
  }

  private Hotswap includes(final String includes) {
    this.includes = pathMatcher(includes);
    return this;
  }

  private Hotswap excludes(final String excludes) {
    this.excludes = pathMatcher(excludes);
    return this;
  }

  public void run() {
    System.out.printf("Hotswap available on: %s\n", dirs);
    System.out.printf("  unlimited runtime class redefinition: %s\n", dcevm
        ? "yes"
        : "no (see https://github.com/dcevm/dcevm)");
    System.out.printf("  includes: %s\n", includes);
    System.out.printf("  excludes: %s\n", excludes);

    this.scanner.start();
    this.startApp();
  }

  private void startApp() {
    if (app != null) {
      stopApp(app);
    }
    executor.execute(() -> {
      URLClassLoader old = loader;
      try {
        this.loader = newClassLoader(cp);
        this.app = loader.loadClass(mainClass).getDeclaredConstructors()[0].newInstance();
        app.getClass().getMethod("start").invoke(app);
      } catch (InvocationTargetException ex) {
        System.err.println("Error found while starting: " + mainClass);
        ex.printStackTrace();
      } catch (Exception ex) {
        System.err.println("Error found while starting: " + mainClass);
        ex.printStackTrace();
      } finally {
        if (old != null) {
          try {
            old.close();
          } catch (Exception ex) {
            System.err.println("Can't close classloader");
            ex.printStackTrace();
          }
          // not sure it how useful is it, but...
        System.gc();
      }
    }
  });
  }

  private static URLClassLoader newClassLoader(final File[] cp) throws MalformedURLException {
    return new URLClassLoader(toURLs(cp), Hotswap.class.getClassLoader()) {
      @Override
      public String toString() {
        return "Hotswap@" + Arrays.toString(cp);
      }
    };
  }

  private void onChange(final Kind<?> kind, final Path path) {
    try {
      Path candidate = relativePath(path);
      if (candidate == null) {
        // System.("Can't resolve path: {}... ignoring it", path);
        return;
      }
      if (!includes.matches(path)) {
        // log.debug("ignoring file {} -> ~{}", path, includes);
        return;
      }
      if (excludes.matches(path)) {
        // log.debug("ignoring file {} -> {}", path, excludes);
        return;
      }
      // reload
      startApp();
    } catch (Exception ex) {
      System.err.printf("Err found while processing: %s\n" + path);
      ex.printStackTrace();
    }
  }

  private Path relativePath(final Path path) {
    for (Path root : paths) {
      if (path.startsWith(root)) {
        return root.relativize(path);
      }
    }
    return null;
  }

  private void stopApp(final Object app) {
    try {
      app.getClass().getMethod("stop").invoke(app);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
        | NoSuchMethodException | SecurityException ex) {
      System.err.println("couldn't stop app");
      ex.printStackTrace();
    }

  }

  static URL[] toURLs(final File[] cp) throws MalformedURLException {
    URL[] urls = new URL[cp.length];
    for (int i = 0; i < urls.length; i++) {
      urls[i] = cp[i].toURI().toURL();
    }
    return urls;
  }

  private static Path[] toPath(final File[] cp) {
    Path[] paths = new Path[cp.length];
    for (int i = 0; i < paths.length; i++) {
      paths[i] = cp[i].toPath();
    }
    return paths;
  }

  private static PathMatcher pathMatcher(final String expressions) {
    List<PathMatcher> matchers = new ArrayList<PathMatcher>();
    for (String expression : expressions.split(",")) {
      matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + expression.trim()));
    }
    return new PathMatcher() {

      @Override
      public boolean matches(final Path path) {
        for (PathMatcher matcher : matchers) {
          if (matcher.matches(path)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public String toString() {
        return "[" + expressions + "]";
      }
    };
  }
}
