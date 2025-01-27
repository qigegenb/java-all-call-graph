package com.adrninistrator.jacg.common.enums;

/**
 * @author adrninistrator
 * @date 2022/8/21
 * @description:
 */
public enum InputDirEnum {
    IDE_CONFIG("_jacg_config", "主要的配置文件"),
    IDE_SQL("_jacg_sql", "保存sql脚本的目录"),
    IDE_KEYWORD_CONF("_jacg_find_keyword", "保存需要查找的关键字的目录"),
    IDE_EXTENSIONS("_jacg_extensions", "保存自定义处理类配置文件的目录"),
    ;

    private final String dirName;
    private final String desc;

    InputDirEnum(String dirName, String desc) {
        this.dirName = dirName;
        this.desc = desc;
    }

    public String getDirName() {
        return dirName;
    }

    public String getDesc() {
        return desc;
    }

    @Override
    public String toString() {
        return dirName;
    }
}
