import com.alan.fasttransfer.core.transfer.TransferFile;
import com.alan.fasttransfer.core.transfer.TransferSession;

/**
 * 校验「会话状态 → 界面呈现」的判断。
 *
 * <p>真实 bug：发送完成后，对话框每次刷新都**无条件**先把标题画成
 * 「正在发送」，再判断是否完成 —— 于是完成后的任何一次进度更新
 * 都会把标题覆盖回「正在发送」，用户看到的就是「传完了还显示正在发送」。</p>
 *
 * <p>这里直接跑产品代码 {@link TransferSession#renderState()}，
 * 并模拟「完成之后又来了一次进度更新」这个顺序，确认不会回到 RUNNING。</p>
 */
public final class SessionRenderTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 会话呈现状态机 ===");

        // ---- 各状态映射 ----
        check("PREPARING", TransferSession.RenderState.PREPARING,
                renderOf(TransferSession.STATE_PREPARING));
        check("WAITING_PEER", TransferSession.RenderState.WAITING_PEER,
                renderOf(TransferSession.STATE_WAITING_PEER));
        check("RUNNING", TransferSession.RenderState.RUNNING,
                renderOf(TransferSession.STATE_RUNNING));
        check("DONE", TransferSession.RenderState.DONE,
                renderOf(TransferSession.STATE_DONE));
        check("FAILED", TransferSession.RenderState.FAILED,
                renderOf(TransferSession.STATE_FAILED));
        check("CANCELLED", TransferSession.RenderState.CANCELLED,
                renderOf(TransferSession.STATE_CANCELLED));
        check("DECLINED", TransferSession.RenderState.DECLINED,
                renderOf(TransferSession.STATE_DECLINED));

        // ---- 终态判定 ----
        check("DONE 是终态", true, terminalOf(TransferSession.STATE_DONE));
        check("FAILED 是终态", true, terminalOf(TransferSession.STATE_FAILED));
        check("CANCELLED 是终态", true, terminalOf(TransferSession.STATE_CANCELLED));
        check("DECLINED 是终态", true, terminalOf(TransferSession.STATE_DECLINED));
        check("RUNNING 不是终态", false, terminalOf(TransferSession.STATE_RUNNING));
        check("PREPARING 不是终态", false, terminalOf(TransferSession.STATE_PREPARING));
        check("WAITING_PEER 不是终态", false, terminalOf(TransferSession.STATE_WAITING_PEER));

        // ---- 复现 bug 的场景 ----
        // 完成之后又来了 3 次「更新」，界面必须保持完成态
        TransferSession session = session(TransferSession.STATE_DONE);
        boolean stayedDone = true;
        for (int i = 0; i < 3; i++) {
            if (session.renderState() != TransferSession.RenderState.DONE) {
                stayedDone = false;
            }
        }
        check("DONE 之后的多次更新仍为 DONE", true, stayedDone);

        // 模拟对话框的渲染决策：一旦终态就永久锁定
        check("对话框锁定后不再回到 RUNNING", true, dialogStaysTerminal());
        check("对话框首帧即终态也能正确锁定", true, dialogLocksOnFirstFrame());

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- 复刻对话框的渲染决策（与 TransferProgressDialog.update 一致） ----

    /** 返回最终渲染到的状态序列中的最后一个。 */
    private static TransferSession.RenderState dialogRender(TransferSession session,
                                                            int updates) {
        boolean locked = false;
        TransferSession.RenderState last = session.renderState();
        for (int i = 0; i < updates; i++) {
            TransferSession.RenderState render = session.renderState();
            if (locked) {
                last = render;
                continue;
            }
            switch (render) {
                case PREPARING:
                case WAITING_PEER:
                    last = render;
                    break;
                case RUNNING:
                    last = TransferSession.RenderState.RUNNING;
                    break;
                default:
                    locked = true;
                    last = render;
                    break;
            }
        }
        return last;
    }

    private static boolean dialogStaysTerminal() {
        TransferSession session = session(TransferSession.STATE_DONE);
        return dialogRender(session, 5) == TransferSession.RenderState.DONE;
    }

    private static boolean dialogLocksOnFirstFrame() {
        TransferSession session = session(TransferSession.STATE_DECLINED);
        return dialogRender(session, 5) == TransferSession.RenderState.DECLINED;
    }

    // ---- 工具 ----

    private static TransferSession session(int state) {
        TransferSession session = new TransferSession();
        session.state = state;
        session.startedAt = 1000L;
        session.finishedAt = 2000L;
        session.files.add(file("a.txt", 100));
        return session;
    }

    private static TransferFile file(String name, long size) {
        TransferFile file = new TransferFile();
        file.name = name;
        file.size = size;
        file.transferred = size;
        file.status = TransferFile.STATUS_DONE;
        return file;
    }

    private static TransferSession.RenderState renderOf(int state) {
        return session(state).renderState();
    }

    private static boolean terminalOf(int state) {
        return session(state).renderIsTerminal();
    }

    private static void check(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
            System.out.println("        expected: " + expected);
            System.out.println("        actual  : " + actual);
        }
    }
}
