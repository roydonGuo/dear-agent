package com.roydon.dear.knowledge.rag.splitter;

import com.roydon.dear.knowledge.domain.bo.FileSplitBO;
import com.roydon.dear.knowledge.enums.FileSplitType;

public class FileSplitterFactory {

    public static FileSplitter getInstance(FileSplitBO splitBO) {
        if (FileSplitType.TITLE.equals(splitBO.splitType())) {
            return new MarkdownHeaderParentTextSplitter(splitBO.chunkSize(), splitBO.overlap());
        }

//        if (FileSplitType.LENGTH.name().equals(splitBO.splitType())) {
//            return new FileByWordSplitter(splitBO.chunkSize(), splitBO.overlap());
//        }
//
//        if (FileSplitType.SEPARATOR.name().equals(splitBO.splitType())) {
//            return new FileByRegexSplitter(splitBO.separator(), "\\n\\n", splitBO.chunkSize(), splitBO.overlap());
//        }

//        if (FileSplitType.REGEX.name().equals(splitBO.splitType())) {
//            return new FileByRegexSplitter(splitBO.regex(), "\\n\\n", splitBO.chunkSize(), splitBO.overlap());
//        }

        if (FileSplitType.SMART.equals(splitBO.splitType())) {
            return new MarkdownHeaderParentTextSplitter(splitBO.chunkSize(), (int) (splitBO.chunkSize() * 0.1));
        }

        return null;
    }

}
