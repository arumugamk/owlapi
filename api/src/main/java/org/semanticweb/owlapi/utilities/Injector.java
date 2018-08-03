package org.semanticweb.owlapi.utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import org.semanticweb.owlapi.model.OntologyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic dependency injection utility, to replace the use of Guice and wrap calls to ServiceLoader.
 */
public class Injector {
    private static final Logger LOGGER = LoggerFactory.getLogger(Injector.class);
    private Map<Object, List<Supplier<?>>> supplierOverrides = new ConcurrentHashMap<>();
    private Map<Object, Class<?>> typesOverrides = new ConcurrentHashMap<>();
    private Map<Object, List<Class<?>>> typesCache = new ConcurrentHashMap<>();
    private Map<URI, AtomicStampedReference<List<String>>> filesCache = new ConcurrentHashMap<>();

    /**
     * Key class for binding overrides
     */
    public static class Key {
        Class<?> c;
        Annotation[] anns;
        int hash = 0;

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key k = (Key) obj;
            return Objects.equals(c, k.c) && Arrays.equals(anns, k.anns);
        }

        /**
         * @param cl class
         * @param a annotations
         * @return modified key
         */
        public Key with(Class<?> cl, Annotation[] a) {
            c = cl;
            anns = a;
            hash = c.hashCode() * 37 + Arrays.hashCode(anns);
            return this;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Default constructor
     */
    public Injector() {
        super();
    }

    /**
     * @param i injector to copy
     */
    public Injector(Injector i) {
        supplierOverrides.putAll(i.supplierOverrides);
        typesOverrides.putAll(i.typesOverrides);
        typesCache.putAll(i.typesCache);

    }

    /**
     * Associate a key made of interface type and optional annotations with an implementation type
     * 
     * @param t implementation type
     * @param c interface type
     * @param annotations annotations
     * @return modified injector
     */
    public Injector bind(Class<?> t, Class<?> c, Annotation... annotations) {
        typesOverrides.put(key(c, annotations), t);
        return this;
    }

    /**
     * Associate a key made of interface type and optional annotations with an instance, replacing
     * existing associations
     * 
     * @param t instance
     * @param c interface type
     * @param annotations annotations
     * @return modified injector
     */
    public Injector bindToOne(Object t, Class<?> c, Annotation... annotations) {
        Supplier<?> s = () -> t;
        return bindToOne(s, c, annotations);
    }

    /**
     * Associate a key made of interface type and optional annotations with a supplier of instances,
     * replacing existing associations
     * 
     * @param t supplier
     * @param c interface type
     * @param annotations annotations
     * @return modified injector
     */
    public Injector bindToOne(Supplier<?> t, Class<?> c, Annotation... annotations) {
        supplierOverrides.put(key(c, annotations), Collections.singletonList(t));
        return this;
    }

    /**
     * Associate a key made of interface type and optional annotations with an instance, adding to
     * existing associations
     * 
     * @param t instance
     * @param c interface type
     * @param annotations annotations
     * @return modified injector
     */
    public <T> Injector bindOneMore(T t, Class<T> c, Annotation... annotations) {
        Supplier<T> s = () -> t;
        return bindOneMore(s, c, annotations);
    }

    /**
     * Associate a key made of interface type and optional annotations with a supplier of instances,
     * adding to existing associations
     * 
     * @param t supplier
     * @param c interface type
     * @param annotations annotations
     * @return modified injector
     */
    public <T> Injector bindOneMore(Supplier<T> t, Class<T> c, Annotation... annotations) {
        supplierOverrides.computeIfAbsent(key(c, annotations), x -> new ArrayList<>()).add(t);
        return this;
    }

    protected Object key(Class<?> c, Annotation... annotations) {
        if (annotations.length == 0) {
            // for input without annotations, use the class as cache key
            return c;
        }
        return new Key().with(c, annotations);
    }

    /**
     * @param t object to inject
     * @return input object with all methods annotated with @Inject having been set with instances.
     */
    public <T> T inject(T t) {
        LOGGER.info("Injecting object {}", t);
        List<Method> methodsToInject = new ArrayList<>();
        Class<?> c = t.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                Inject annotation = m.getAnnotation(Inject.class);
                if (annotation != null) {
                    methodsToInject.add(m);
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : methodsToInject) {
            Parameter[] parameterTypes = m.getParameters();
            Object[] args = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Parameter arg = parameterTypes[i];
                if (Collection.class.isAssignableFrom(arg.getType())) {
                    Class<?> type = (Class<?>) ((ParameterizedType) arg.getParameterizedType())
                        .getActualTypeArguments()[0];
                    args[i] =
                        load(type, qualifiers(arg.getAnnotations())).collect(Collectors.toSet());
                } else {
                    args[i] = load(arg.getType(), qualifiers(arg.getAnnotations())).findAny()
                        .orElse(null);
                }
            }
            try {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Injecting values {} on method {}.", Arrays.toString(args), m);
                }
                m.invoke(t, args);
            } catch (IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
                LOGGER.error("Injection failed", e);
            }
        }
        return t;
    }

    private static Annotation[] qualifiers(Annotation[] anns) {
        List<Annotation> qualifiers = new ArrayList<>();
        for (Annotation a : anns) {
            if (a instanceof Qualifier
                || a.annotationType().getAnnotation(Qualifier.class) != null) {
                qualifiers.add(a);
            }
        }
        return qualifiers.toArray(new Qualifier[qualifiers.size()]);
    }

    /**
     * @param c class
     * @param qualifiers optional annotations
     * @return instance
     */
    public <T> T getImplementation(Class<T> c, Annotation... qualifiers) {
        return load(c, qualifiers).findAny().orElse(null);
    }

    /**
     * @param c type to look up
     * @param qualifiers optional qualifiers
     * @return all implementations for the arguments
     */
    public <T> Stream<T> getImplementations(Class<T> c, Annotation... qualifiers) {
        return load(c, qualifiers);
    }

    /**
     * @param c type to look up
     * @param v local override for configuration properties
     * @param qualifiers optional qualifiers
     * @return implementation for the arguments (first of the list if multiple ones exist)
     */
    public <T> T getImplementation(Class<T> c, OntologyConfigurator v, Annotation... qualifiers) {
        return new Injector(this).bindToOne(v, OntologyConfigurator.class).getImplementation(c,
            qualifiers);
    }

    /**
     * @param c class
     * @param overrides local overrides of existing bindings
     * @param qualifiers optional annotations
     * @return instance
     */
    public <T> T getImplementation(Class<T> c, Map<Object, List<Supplier<?>>> overrides,
        Annotation... qualifiers) {
        Injector i = new Injector(this);
        overrides.forEach((a, b) -> i.supplierOverrides.put(a, b));
        return i.getImplementation(c, qualifiers);
    }

    protected ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            // in OSGi, the context class loader is likely null.
            // This would trigger the use of the system class loader, which would
            // not see the OWLAPI jar, nor any other jar containing implementations.
            // In that case, use this class classloader to load, at a minimum, the
            // services provided by the OWLAPI jar itself.
            loader = getClass().getClassLoader();
        }
        return loader;
    }

    /**
     * @param type type to load
     * @param qualifiers qualifying annotations
     * @param <T> return type
     * @return iterable over T implementations
     */
    protected <T> Stream<T> load(Class<T> type, Annotation... qualifiers) {
        Object key = key(type, qualifiers);
        Class<?> c = typesOverrides.get(key);
        if (c != null) {
            return Stream.of(type.cast(instantiate(c, key)));
        }
        List<Supplier<?>> suppliers = supplierOverrides.getOrDefault(key, Collections.emptyList());
        if (!suppliers.isEmpty()) {
            // cached suppliers
            return suppliers.stream().map(Supplier::get).map(type::cast);
        }
        List<Class<?>> cached = typesCache.getOrDefault(key, Collections.emptyList());
        if (!cached.isEmpty()) {
            return cached.stream().map(s -> instantiate(s, key)).map(type::cast);
        }
        String name = "META-INF/services/" + type.getName();
        LOGGER.info("Loading file {}", name);
        // J2EE compatible search
        return urls(name).flatMap(this::entries).distinct()
            .map(s -> (Class<T>) prepareClass(s, key)).map(s -> instantiate(s, key));
    }

    private static <T> Constructor<T> injectableConstructor(Class<T> c) {
        try {
            Constructor<?>[] constructors = c.getConstructors();
            if (constructors.length == 1) {
                return (Constructor<T>) constructors[0];
            }
            Optional<Constructor<?>> findAny = Arrays.stream(constructors)
                .filter(m -> m.getAnnotation(Inject.class) != null).findAny();
            if (!findAny.isPresent()) {
                LOGGER.error("No injectable constructor found for {}", c);
            }
            return (Constructor<T>) findAny.orElse(null);
        } catch (SecurityException e) {
            LOGGER.error(
                "No injectable constructor found for " + c + " because of security restrictions",
                e);
            return null;
        }
    }

    private Object[] paramInstances(Parameter[] params) {
        Object[] toReturn = new Object[params.length];
        for (int i = 0; i < toReturn.length; i++) {
            Iterator<?> iterator = load(params[i].getType(), params[i].getAnnotations()).iterator();
            if (iterator.hasNext()) {
                toReturn[i] = iterator.next();
            } else {
                LOGGER.error("No instantiation found for {}", params[i]);
            }
        }
        return toReturn;
    }

    private <T> Class<T> prepareClass(String s, Object key) {
        try {
            Class<?> forName = Class.forName(s);
            typesCache.computeIfAbsent(key, x -> new ArrayList<>()).add(forName);
            return (Class<T>) forName;
        } catch (ClassNotFoundException | IllegalArgumentException | SecurityException e) {
            LOGGER.error("Instantiation failed", e);
            return null;
        }
    }


    private <T> T instantiate(Class<T> s, Object key) {
        try {
            Constructor<?> c = injectableConstructor(s);
            if (c == null) {
                LOGGER.error("Instantiation failed: no constructors found for {}", s);
                return null;
            }
            Object[] paramInstances = paramInstances(c.getParameters());
            Object newInstance = c.newInstance(paramInstances);
            if (s.getAnnotation(Singleton.class) != null) {
                // cache singletons for next call
                supplierOverrides.put(key, Collections.singletonList(() -> newInstance));
            }
            return s.cast(newInstance);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
            | InvocationTargetException | SecurityException e) {
            LOGGER.error("Instantiation failed", e);
            return null;
        }
    }

    private Stream<URI> urls(String name) {
        List<URI> l = new ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader().getResources(name);
            while (resources.hasMoreElements()) {
                l.add(resources.nextElement().toURI());
            }
            if (l.isEmpty()) {
                LOGGER.warn("No files found for {}", name);
            }
        } catch (IOException | URISyntaxException e) {
            LOGGER.error("Error accessing services files", e);
        }
        return l.stream();
    }

    private Stream<String> entries(URI c) {
        int time = (int) (System.currentTimeMillis() & 0x00000000FFFFFFFFL);
        AtomicStampedReference<List<String>> l = filesCache.get(c);
        if (l == null || time - l.getStamp() > 30000) {
            // no cache or oldest value is more than 30 seconds old
            l = new AtomicStampedReference<>(actualRead(c), time);
            filesCache.put(c, l);
        }
        return l.getReference().stream();
    }

    protected List<String> actualRead(URI c) {
        try (InputStream in = c.toURL().openStream();
            InputStreamReader in2 = new InputStreamReader(in, StandardCharsets.UTF_8);
            BufferedReader r = new BufferedReader(in2)) {
            return r.lines().collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Error reading services files: " + c, e);
            return Collections.emptyList();
        }
    }

}
