# 自定义 Elasticsearch 镜像：内置 IK 中文分词插件
# LocalLife Server 的 shop 搜索索引用 ik_max_word 分词器，必须预装此插件，
# 否则启动报错：Custom Analyzer [ik_max_word_analyzer] failed to find tokenizer under name [ik_max_word]
#
# 固化为镜像后，即使 docker compose down -v 删除容器也无需手动重装插件。
#
# 插件 zip 已预下载到本地（build 容器内 DNS 解析 release.infinilabs.com 会失败，
# 故用 file:// 本地安装，不依赖 build 阶段联网）。
# 如需更新版本：
#   curl -L -o analysis-ik-<ver>.zip https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-<ver>.zip

FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.3

COPY analysis-ik-8.17.3.zip /tmp/analysis-ik.zip

# analysis-ik 版本必须与 ES 版本严格一致
# （非 root 用户无法 rm /tmp 文件，4.5MB zip 残留无影响，不做清理）
RUN bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip
