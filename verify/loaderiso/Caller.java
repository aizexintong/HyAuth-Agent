package loaderiso;

/**
 * 诊断用：自身完全不引用缺失类型，但**调用**了一个方法体引用缺失类型的类。
 *
 * <p>如果对 {@code Caller} 反射也失败，说明类校验（verification）会顺着调用链解析到被调类；
 * 如果只有 {@code MissingTypeUser} 失败，说明校验只解析本类自己引用的类型。
 */
public class Caller {

    public static Object call() {
        return MissingTypeUser.body();
    }

    public static Class<?> callLiteral() {
        return MissingTypeUser.literal();
    }
}
