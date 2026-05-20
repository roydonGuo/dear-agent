package com.roydon.dear.knowledge.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件 mineType
 * <p>
 * text/markdown
 * text/plain
 * application/pdf
 * image/jpeg
 * image/png
 * image/gif
 * image/webp
 * image/svg+xml
 * video/mp4
 * video/webm
 * video/quicktime
 * video/x-msvideo
 * video/x-matroska
 * audio/mpeg
 * audio/wav
 * application/msword
 * application/vnd.openxmlformats-officedocument.wordprocessingml.document
 * application/vnd.ms-excel
 * application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
 * application/vnd.ms-powerpoint
 * application/vnd.openxmlformats-officedocument.presentationml.presentation
 * text/csv
 * application/json
 * application/xml
 * text/html
 * text/css
 * application/javascript
 * text/x-java-source
 * text/x-python
 * text/yaml
 * application/octet-stream
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Getter
@AllArgsConstructor
public enum FileMineType {
    TEXT_MARKDOWN("text/markdown"),
    TEXT_PLAIN("text/plain"),
    APPLICATION_PDF("application/pdf"),
    IMAGE_JPEG("image/jpeg"),
    IMAGE_PNG("image/png"),
    IMAGE_GIF("image/gif"),
    IMAGE_WEBP("image/webp"),
    IMAGE_SVG_XML("image/svg+xml"),
    VIDEO_MP4("video/mp4"),
    VIDEO_WEBM("video/webm"),
    VIDEO_QUICKTIME("video/quicktime"),
    VIDEO_X_MSVIDEO("video/x-msvideo"),
    VIDEO_X_MATROSKA("video/x-matroska"),
    AUDIO_MPEG("audio/mpeg"),
    AUDIO_WAV("audio/wav"),
    APPLICATION_MSWORD("application/msword"),
    APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_WORDPROCESSINGML_DOCUMENT("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    APPLICATION_VND_MS_EXCEL("application/vnd.ms-excel"),
    APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_SPREADSHEETML_SHEET("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    APPLICATION_VND_MS_POWERPOINT("application/vnd.ms-powerpoint"),
    APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_PRESENTATION("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    TEXT_CSV("text/csv"),
    APPLICATION_JSON("application/json"),
    APPLICATION_XML("application/xml"),
    TEXT_HTML("text/html"),
    TEXT_CSS("text/css"),
    APPLICATION_JAVASCRIPT("application/javascript"),
    TEXT_X_JAVA_SOURCE("text/x-java-source"),
    TEXT_X_PYTHON("text/x-python"),
    TEXT_YAML("text/yaml"),
    APPLICATION_OCTET_STREAM("application/octet-stream");

    private final String value;

}
