package ai.proxyma.core.chat.agents;

import jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A copy of Constellations' test fake (itself a copy of key accounts'), with LessThan(Equal), findTopN and a
 * hook for the queries a name cannot say (CUSTOM): Spring Data repositories backed by a map, for driving the services in a test without a database or
 * a Spring context - the suite stays at seconds, as the rest of test:backend does.
 *
 * <p>Answers the CrudRepository methods the services call, and derived queries BY THEIR NAME
 * ({@code findByAccountIdAndRemovedAtIsNull}, {@code findFirstBy...OrderByOccurredAtDesc},
 * {@code deleteByAccountId}), so a repository method this does not understand fails the test loudly
 * rather than answering something plausible. {@code save} bumps {@code @Version} as Hibernate's flush
 * would, which is what lets a stale-version test mean something.
 */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    /** An answer for a hand-written query, given the repository's rows and the call's arguments. */
    interface Custom {
        Object answer(Map<Object, Object> store, Object[] args) throws Exception;
    }

    @SuppressWarnings("unchecked")
    static <R> R of(Class<R> repositoryType) {
        return of(repositoryType, Map.of());
    }

    @SuppressWarnings("unchecked")
    static <R> R of(Class<R> repositoryType, Map<String, Custom> custom) {
        Map<Object, Object> store = new LinkedHashMap<>();
        InvocationHandler handler = (proxy, method, args) -> custom.containsKey(method.getName())
                ? custom.get(method.getName()).answer(store, args == null ? new Object[0] : args)
                : handle(store, method, args == null ? new Object[0] : args);
        return (R) Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType}, handler);
    }

    private static Object handle(Map<Object, Object> store, Method method, Object[] args) throws Exception {
        String name = method.getName();
        switch (name) {
            case "save", "saveAndFlush":
                return save(store, args[0]);
            case "saveAll": {
                List<Object> out = new ArrayList<>();
                for (Object o : (Iterable<?>) args[0]) out.add(save(store, o));
                return out;
            }
            case "findById", "findByIdForUpdate":
                return Optional.ofNullable(store.get(args[0]));
            case "existsById":
                return store.containsKey(args[0]);
            case "findAll":
                if (args.length == 0) return new ArrayList<>(store.values());
                break;
            case "findAllById": {
                List<Object> out = new ArrayList<>();
                for (Object id : (Iterable<?>) args[0]) if (store.containsKey(id)) out.add(store.get(id));
                return out;
            }
            case "delete":
                store.remove(idOf(args[0]));
                return null;
            case "deleteById":
                store.remove(args[0]);
                return null;
            case "deleteAll":
                if (args.length == 0) store.clear();
                else for (Object o : new ArrayList<>((Collection<?>) toCollection(args[0]))) store.remove(idOf(o));
                return null;
            case "count":
                if (args.length == 0) return (long) store.size();
                break;
            case "flush":
                return null;
            case "toString":
                return "InMemoryRepository" + store.keySet();
            case "hashCode":
                return System.identityHashCode(store);
            case "equals":
                return false;
            default:
                break;
        }
        return derived(store, method, args);
    }

    private static Collection<?> toCollection(Object iterable) {
        List<Object> out = new ArrayList<>();
        for (Object o : (Iterable<?>) iterable) out.add(o);
        return out;
    }

    private static Object save(Map<Object, Object> store, Object entity) throws Exception {
        Field id = field(entity.getClass(), Id.class);
        if (id.get(entity) == null && id.getType() == UUID.class) id.set(entity, UUID.randomUUID());
        Field version = findField(entity.getClass(), Version.class);
        if (version != null) {
            Long v = (Long) version.get(entity);
            version.set(entity, v == null ? 0L : v + 1);
        }
        store.put(id.get(entity), entity);
        return entity;
    }

    // ---- derived queries ----

    private record Criterion(String property, String kind) {}

    private static Object derived(Map<Object, Object> store, Method method, Object[] args) throws Exception {
        String name = method.getName();
        String prefix;
        int limit = Integer.MAX_VALUE;
        java.util.regex.Matcher top = java.util.regex.Pattern.compile("^findTop(\\d+)By").matcher(name);
        if (top.find()) {
            limit = Integer.parseInt(top.group(1));
            name = "findBy" + name.substring(top.end());
        }
        if (name.startsWith("findFirstBy")) prefix = "findFirstBy";
        else if (name.startsWith("findBy")) prefix = "findBy";
        else if (name.startsWith("deleteBy")) prefix = "deleteBy";
        else if (name.startsWith("countBy")) prefix = "countBy";
        else if (name.startsWith("existsBy")) prefix = "existsBy";
        else throw new UnsupportedOperationException("InMemoryRepositories does not answer " + name);

        String rest = name.substring(prefix.length());
        String order = null;
        int orderAt = rest.indexOf("OrderBy");
        if (orderAt >= 0) {
            order = rest.substring(orderAt + "OrderBy".length());
            rest = rest.substring(0, orderAt);
        }
        List<Criterion> criteria = new ArrayList<>();
        for (String part : rest.split("And")) {
            if (part.endsWith("IsNotNull")) criteria.add(new Criterion(part.substring(0, part.length() - 9), "notnull"));
            else if (part.endsWith("IsNull")) criteria.add(new Criterion(part.substring(0, part.length() - 6), "null"));
            else if (part.endsWith("LessThanEqual")) criteria.add(new Criterion(part.substring(0, part.length() - 13), "lte"));
            else if (part.endsWith("LessThan")) criteria.add(new Criterion(part.substring(0, part.length() - 8), "lt"));
            else if (part.endsWith("After")) criteria.add(new Criterion(part.substring(0, part.length() - 5), "gt"));
            else if (part.endsWith("In")) criteria.add(new Criterion(part.substring(0, part.length() - 2), "in"));
            else criteria.add(new Criterion(part, "eq"));
        }

        List<Object> matches = new ArrayList<>();
        for (Object row : store.values()) {
            int arg = 0;
            boolean ok = true;
            for (Criterion c : criteria) {
                Object value = property(row, c.property());
                switch (c.kind()) {
                    case "null" -> ok &= value == null;
                    case "notnull" -> ok &= value != null;
                    case "in" -> ok &= ((Collection<?>) args[arg++]).contains(value);
                    case "lte" -> {
                        Object bound = args[arg++];
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        boolean le = value != null && ((Comparable) value).compareTo(bound) <= 0;
                        ok &= le;
                    }
                    case "lt" -> {
                        Object bound = args[arg++];
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        boolean lt = value != null && ((Comparable) value).compareTo(bound) < 0;
                        ok &= lt;
                    }
                    case "gt" -> {
                        Object bound = args[arg++];
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        boolean gt = value != null && ((Comparable) value).compareTo(bound) > 0;
                        ok &= gt;
                    }
                    default -> ok &= Objects.equals(value, args[arg++]);
                }
            }
            if (ok) matches.add(row);
        }
        if (order != null) {
            boolean desc = order.endsWith("Desc");
            String property = order.replaceAll("(Asc|Desc)$", "");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Comparator<Object> cmp = Comparator.comparing(r -> (Comparable) property(r, property),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            matches.sort(desc ? cmp.reversed() : cmp);
        }
        if (matches.size() > limit) matches = new ArrayList<>(matches.subList(0, limit));

        switch (prefix) {
            case "deleteBy":
                for (Object row : matches) store.remove(idOf(row));
                return method.getReturnType() == int.class ? matches.size() : (Object) (long) matches.size();
            case "countBy":
                return (long) matches.size();
            case "existsBy":
                return !matches.isEmpty();
            case "findFirstBy":
                return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
            default:
                if (method.getReturnType() == org.springframework.data.domain.Page.class) {
                    org.springframework.data.domain.Pageable page = (org.springframework.data.domain.Pageable) args[args.length - 1];
                    // The Pageable's own sort, as a database applies it: each order in turn, nulls last.
                    Comparator<Object> bySort = null;
                    for (org.springframework.data.domain.Sort.Order o : page.getSort()) {
                        String property = Character.toUpperCase(o.getProperty().charAt(0)) + o.getProperty().substring(1);
                        boolean ascending = o.isAscending();
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Comparator<Object> one = (a, b) -> {
                            Comparable x = (Comparable) property(a, property);
                            Comparable y = (Comparable) property(b, property);
                            if (x == null || y == null) return x == y ? 0 : x == null ? 1 : -1;
                            return ascending ? x.compareTo(y) : y.compareTo(x);
                        };
                        bySort = bySort == null ? one : bySort.thenComparing(one);
                    }
                    if (bySort != null) matches.sort(bySort);
                    int from = (int) Math.min(matches.size(), page.getOffset());
                    int to = Math.min(matches.size(), from + page.getPageSize());
                    return new org.springframework.data.domain.PageImpl<>(matches.subList(from, to), page, matches.size());
                }
                if (method.getReturnType() == Optional.class) {
                    if (matches.size() > 1) throw new IllegalStateException(name + " found " + matches.size() + " rows");
                    return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
                }
                return matches;
        }
    }

    private static Object property(Object row, String property) {
        String getter = "get" + property;
        String is = "is" + property;
        for (Method m : row.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && (m.getName().equals(getter) || m.getName().equals(is))) {
                try {
                    return m.invoke(row);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException(row.getClass().getSimpleName() + " has no property " + property);
    }

    private static Object idOf(Object entity) {
        try {
            return field(entity.getClass(), Id.class).get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        Field f = findField(type, annotation);
        if (f == null) throw new IllegalStateException(type.getSimpleName() + " has no @" + annotation.getSimpleName());
        return f;
    }

    private static Field findField(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(annotation)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }
}
