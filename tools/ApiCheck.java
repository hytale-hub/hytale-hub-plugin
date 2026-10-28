import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Prueft, ob jeder Aufruf aus dem Plugin (kompilierte Klassen) an com.hypixel.* im angegebenen
 * Server-Jar with exactly the same descriptor (return type included).
 * Usage: java -cp tools/bin ApiCheck <plugin-classes-dir> <server.jar> [weitere Libs...]
 * Exit code 1 on mismatch. Catches NoSuchMethodError/NoSuchFieldError before a release.
 */
public class ApiCheck {
    static final Pattern REF = Pattern.compile("//\\s*(Method|InterfaceMethod|Field)\\s+(com/hypixel/[^.\\s]+)\\.([^:\\s]+):(\\S+)");

    public static void main(String[] a) throws Exception {
        Path classes = Paths.get(a[0]);
        List<URL> urls = new ArrayList<>();
        for (int i = 1; i < a.length; i++) urls.add(Paths.get(a[i]).toUri().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ApiCheck.class.getClassLoader());

        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(classes)) { s.filter(p -> p.toString().endsWith(".class")).forEach(files::add); }

        Set<String> refs = new TreeSet<>();
        for (Path f : files) {
            Process p = new ProcessBuilder("javap", "-v", "-p", f.toString()).redirectErrorStream(true).start();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = REF.matcher(line);
                    if (m.find()) refs.add(m.group(1) + " " + m.group(2) + "." + m.group(3) + ":" + m.group(4));
                }
            }
            p.waitFor();
        }

        int bad = 0;
        Pattern split = Pattern.compile("(\\w+) ([^.]+)\\.([^:]+):(.+)");
        for (String ref : refs) {
            Matcher m = split.matcher(ref);
            m.matches();
            String kind = m.group(1), owner = m.group(2).replace('/', '.'), name = m.group(3).replace("\"", ""), desc = m.group(4);
            try {
                Class<?> c = Class.forName(owner, false, cl);
                if (!found(c, kind, name, desc, new HashSet<>())) { bad++; System.out.println("MISSING " + owner + "." + name + ":" + desc); }
            } catch (Throwable t) {
                bad++; System.out.println("NO CLASS " + owner + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        System.out.println(refs.size() + " API references checked, " + bad + " failing");
        System.exit(bad == 0 ? 0 : 1);
    }

    static boolean found(Class<?> c, String kind, String name, String desc, Set<Class<?>> seen) {
        if (c == null || !seen.add(c)) return false;
        if (kind.equals("Field")) {
            for (Field f : c.getDeclaredFields()) if (f.getName().equals(name) && sig(f.getType()).equals(desc)) return true;
        } else if (name.equals("<init>")) {
            for (Constructor<?> k : c.getDeclaredConstructors()) if (sig(k).equals(desc)) return true;
            return false;
        } else {
            for (Method mm : c.getDeclaredMethods()) if (mm.getName().equals(name) && sig(mm).equals(desc)) return true;
        }
        if (found(c.getSuperclass(), kind, name, desc, seen)) return true;
        for (Class<?> i : c.getInterfaces()) if (found(i, kind, name, desc, seen)) return true;
        return false;
    }

    static String sig(Class<?> t) {
        if (t.isArray()) return "[" + sig(t.getComponentType());
        if (t == void.class) return "V"; if (t == int.class) return "I"; if (t == long.class) return "J";
        if (t == boolean.class) return "Z"; if (t == double.class) return "D"; if (t == float.class) return "F";
        if (t == byte.class) return "B"; if (t == short.class) return "S"; if (t == char.class) return "C";
        return "L" + t.getName().replace('.', '/') + ";";
    }
    static String sig(Method m) { StringBuilder b = new StringBuilder("("); for (Class<?> p : m.getParameterTypes()) b.append(sig(p)); return b.append(")").append(sig(m.getReturnType())).toString(); }
    static String sig(Constructor<?> m) { StringBuilder b = new StringBuilder("("); for (Class<?> p : m.getParameterTypes()) b.append(sig(p)); return b.append(")V").toString(); }
}
