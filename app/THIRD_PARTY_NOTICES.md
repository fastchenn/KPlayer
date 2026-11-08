# 应用层第三方组件

## pinyin4j 2.5.1

- Maven 坐标：`com.belerweb:pinyin4j:2.5.1`。
- 用途：将中文文件及文件夹名称转换为不带声调的拼音排序键，与英文名称混合排序；不修改显示名称或请求路径。
- 上游及源码：https://github.com/belerweb/pinyin4j
- 原始源码归档：https://repo.maven.apache.org/maven2/com/belerweb/pinyin4j/2.5.1/pinyin4j-2.5.1-sources.jar
- 上游源文件声明 GNU GPL version 2 or later，保留上游版权及许可声明；应用按 GPL-3.0-or-later 分发，完整 GPL v3 许可见根目录 `LICENSE`。Maven POM 另标注 BSD，本项目保守沿用源文件中的 GPL 声明。
- 应用仅调用库 API，未修改上游库。拼音使用字典中的首个读音，不保证所有多音字词语的语义读音；无拼音记录的字符保留用于稳定排序。
