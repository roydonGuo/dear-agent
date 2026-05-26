package com.roydon.dear.knowledge.process.chain;

/**
 * 责任链抽象 —— 子类实现 {@link #handle(FileProcessContext)}，
 * 完成后调用 {@link #processNext(FileProcessContext)} 将上下文传递给下一个处理器。
 */
public abstract class AbstractFileProcessHandler {

    protected AbstractFileProcessHandler next;

    public void setNext(AbstractFileProcessHandler next) {
        this.next = next;
    }

    /**
     * 处理当前步骤，完成后调用 {@link #processNext} 触发下一个处理器。
     */
    public abstract void handle(FileProcessContext ctx);

    /**
     * 传递给下一个处理器（若存在）。
     */
    protected void processNext(FileProcessContext ctx) {
        if (next != null) {
            next.handle(ctx);
        }
    }
}
