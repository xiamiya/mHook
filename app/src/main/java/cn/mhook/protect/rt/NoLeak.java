package cn.mhook.protect.rt;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * 日志防泄漏运行期辅助类：out/err 为空输出流，tr 为空实现。
 * 被 DexNoLeak 重定向 System.out/err 与 Throwable.printStackTrace 使用。
 */
public class NoLeak {

    public static final PrintStream out = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    });

    public static final PrintStream err = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    });

    public static void tr(Throwable t) {
    }
}
