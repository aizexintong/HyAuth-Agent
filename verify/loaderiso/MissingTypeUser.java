package loaderiso;

/**
 * 诊断用：方法体里 {@code new} 了一个「当前加载器看不见的类型」。
 * <p>它只用 {@code throws} 之外的字节码引用（NEW / ATHROW / LDC），没有任何签名引用，
 * 用来判断反射（{@code getDeclaredMethods()}）到底会不会因为方法体里的缺失类型而失败。
 *
 * <p>编译时需要 authlib；运行时故意不提供。
 */
public class MissingTypeUser {

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable throwable) throws T {
        throw (T) throwable;
    }

    public static Object body() {
        sneaky(new com.mojang.authlib.exceptions.AuthenticationUnavailableException("probe"));
        return null;
    }

    public static Class<?> literal() {
        return com.mojang.authlib.GameProfile.class;
    }
}
