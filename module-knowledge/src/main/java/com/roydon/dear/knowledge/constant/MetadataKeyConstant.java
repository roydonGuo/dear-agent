package com.roydon.dear.knowledge.constant;

/**
 * 元数据的键常量
 */
public class MetadataKeyConstant {
    /**
     * 文件名称
     */
    public static final String FILE_NAME = "fileName";

    public static final String BASE_ID = "baseId";

    public static final String FILE_ID = "fileId";

    /**
     * 分段唯一标识（雪花ID），用于关联父子分段
     */
    public static final String CHUNK_ID = "chunkId";

    /**
     * 父分段ID，指向所属父分段的 chunkId。
     * 子 chunk 用于向量检索（粒度细），父分段包含完整语义上下文（粒度粗）。
     */
    public static final String PARENT_CHUNK_ID = "parentChunkId";

    /**
     * 父分段完整文本（非持久化，仅检索管道内使用）。
     * 在 ParentChunkEnrichmentStage 中从 DB 回查后注入 metadata，
     * 供 formatAsContext 优先使用父分段全文替代子 chunk 文本。
     */
    public static final String PARENT_CHUNK_TEXT = "parentChunkText";

    /**
     * 同级块ID
     */
    public static final String BROTHER_CHUNK_ID = "brotherChunkId";


    public static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";

    public static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    /**
     * 头级别
     */
    public static final String HEADER_LEVEL = "headerLevel";

    /**
     * 访问权限
     */
    public static final String ACCESSIBLE_BY = "accessibleBy";

    /**
     * 文件地址
     */
    public static final String URL = "url";
    public static final String PATH = "path";

    /**
     * 文件版本
     */
    public static final String VERSION = "version";

    /**
     * 分类
     */
    public static final String CATEGORY = "category";

    /**
     * 摘要
     */
    public static final String SUMMARY = "summary";

    /**
     * 关键字
     */
    public static final String KEYWORDS = "keywords";

    /**
     * 跳过embedding标记，true表示不需要做embedding
     */
    public static final String SKIP_EMBEDDING = "skipEmbedding";
}
