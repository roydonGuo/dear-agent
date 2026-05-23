package com.roydon.dear.knowledge.rag.splitter;


import com.roydon.dear.knowledge.domain.bo.FileSplitBO;

/**
 * @Author Hollis
 */
public class DocumentSplitterFactory {
    public static DocumentSplitter getInstance(FileSplitBO splitBO) {
        if (SplitType.TITLE.name().equals(splitBO.splitType())) {
            return new MarkdownHeaderParentTextSplitter(splitBO.chunkSize(), splitBO.overlap());
        }

        if (SplitType.LENGTH.name().equals(splitBO.splitType())) {
            return new DocumentByWordSplitter(splitBO.chunkSize(), splitBO.overlap());
        }

        if (SplitType.SEPARATOR.name().equals(splitBO.splitType())) {
            return new DocumentByRegexSplitter(splitBO.separator(), "\\n\\n", splitBO.chunkSize(), splitBO.overlap());
        }

        if (SplitType.REGEX.name().equals(splitBO.splitType())) {
            return new DocumentByRegexSplitter(splitBO.regex(), "\\n\\n", splitBO.chunkSize(), splitBO.overlap());
        }

        if (SplitType.SMART.name().equals(splitBO.splitType())) {
            return new MarkdownHeaderParentTextSplitter(splitBO.chunkSize(), (int) (splitBO.chunkSize() * 0.1));
        }

        return null;
    }
}
