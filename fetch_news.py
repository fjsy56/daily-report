"""
每日一报 · 新闻自动抓取脚本
由 GitHub Actions 每天 12:00 自动执行
"""
import json, re, sys, os
from datetime import datetime
from xml.etree import ElementTree as ET
import urllib.request
from collections import Counter
from collections import Counter

TIMEOUT = 20
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; DailyReport/1.0)"}

def fetch(url):
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.read().decode("utf-8", errors="ignore")
    except Exception as e:
        print(f"  ⚠ 抓取失败: {url[:60]} -> {e}")
        return ""

def parse_rss(html, max_n=6):
    items = []
    try:
        root = ET.fromstring(html)
        for el in root.iter("item"):
            t = (el.findtext("title") or "").strip()
            l = (el.findtext("link") or "").strip()
            d = (el.findtext("description") or "").strip()
            d = re.sub(r"<[^>]+>", "", d)[:200]
            if t and l:
                items.append({"title": t, "link": l, "desc": d})
            if len(items) >= max_n:
                break
    except ET.ParseError:
        pass
    return items

def clean_text(s):
    """清理摘要：HTML实体转义、压缩空白、截断"""
    if not s:
        return ""
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&middot;", "·")
    s = s.replace("&mdash;", "—").replace("&ndash;", "–").replace("&quot;", '"')
    s = s.replace("&#39;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”")
    s = re.sub(r"\s+", " ", s).strip()
    return s[:130]

# ---- 关键词 / 热词提取（今日速览关键词总结 + 热词统计，随新闻自动更新） ----
STOP_WORDS = set("的 了 与 和 及 在 是 将 称 为 或 其 该 新 今日 首次 正式 发布 宣布 推出 上线 "
                 "国内 全球 最新 曝 曝光 突发 重磅 消息 报道 记者 编辑 来源 点击 查看 全文 详情 "
                 "我们 它们 这些 那些 一个 这个 那个 已经 开始 成为 进行 表示 透露 回应 确认".split())

def _split_words(title):
    """从单个标题提取词元：英文词 + 中文短片段（2-8字，过滤停用词）"""
    out = []
    for m in re.findall(r"[A-Za-z][A-Za-z0-9.+-]{1,24}", title or ""):
        out.append(m)
    for p in re.split(r"[｜|：:;；、，,。！？!?（）()\[\]【】\s]+", title or ""):
        p = p.strip()
        if 2 <= len(p) <= 8 and p not in STOP_WORDS and not re.search(r"[A-Za-z0-9]", p):
            out.append(p)
    return out

def extract_keywords(texts, top_n=6):
    """从一批标题中提取高频关键词（今日速览用：提取核心要点而非照搬原文）"""
    counter = Counter()
    for t in texts:
        for w in _split_words(t):
            counter[w] += 1
    return [w for w, _ in counter.most_common(top_n * 3) if len(w) >= 2][:top_n]

def build_hotwords(texts, top_n=20):
    """全站热词统计：词频归一化为 0-1 权重（前端词云样式用）"""
    counter = Counter()
    for t in texts:
        for w in _split_words(t):
            counter[w] += 1
    top = counter.most_common(top_n)
    if not top:
        return []
    mx = top[0][1]
    return [{"w": w, "wt": round(0.35 + 0.6 * (c / mx), 2)} for w, c in top]

def extract_tag(title):
    """从标题提取领域/公司标签（公司名优先，其次技术领域，最后融资类兜底）"""
    rules = [
        ("DeepSeek", ["DeepSeek", "深度求索"]),
        ("OpenAI", ["OpenAI", "ChatGPT"]),
        ("谷歌", ["谷歌", "Google", "Gemini", "DeepMind", "Android"]),
        ("字节", ["字节", "抖音", "豆包", "飞书", "TikTok"]),
        ("英伟达", ["英伟达", "NVIDIA", "CUDA", "GPU"]),
        ("微软", ["微软", "Microsoft", "Copilot", "Windows"]),
        ("Meta", ["Meta", "Facebook", "扎克伯格"]),
        ("苹果", ["苹果", "iPhone", "Apple"]),
        ("华为", ["华为", "鸿蒙", "麒麟", "昇腾", "余承东"]),
        ("小米", ["小米"]),
        ("AMD", ["AMD"]),
        ("马斯克", ["马斯克", "SpaceX", "特斯拉", "星舰"]),
        ("阿里", ["阿里", "千问", "通义", "淘宝"]),
        ("百度", ["百度", "文心"]),
        ("腾讯", ["腾讯", "微信"]),
        ("宇树", ["宇树"]),
        ("具身智能", ["具身", "人形", "机器人", "智元", "众擎"]),
        ("芯片", ["芯片", "半导体", "晶圆", "光刻", "HBM"]),
        ("AI安全", ["安全", "对齐", "滥用"]),
        ("大模型", ["大模型", "模型", "MoE", "Transformer", "参数", "开源"]),
        ("Agent", ["Agent", "智能体", "插件"]),
        ("视频生成", ["视频生成", "文生视频", "Sora"]),
        ("自动驾驶", ["自动驾驶", "智驾", "FSD", "驾驶"]),
        ("太空AI", ["太空", "卫星", "轨道"]),
        ("操作系统", ["操作系统", "OS"]),
        ("融资", ["融资", "IPO", "估值", "上市", "收购", "投资", "融资"]),
    ]
    tl = title.lower()
    for tag, kws in rules:
        for kw in kws:
            if kw.lower() in tl:
                return tag
    return "行业动态"

def tech_keywords(title):
    """根据标题关键词自动分类"""
    t = title
    # 前沿科技
    tech_kw = ["架构", "算法", "模型发布", "开源", "论文", "量子", "核聚变",
               "突破", "新方法", "数学", "蛋白质", "基因", "MoE", "Transformer",
               "基准", "CVPR", "NeurIPS", "ICML", "训练", "参数", "RL", "强化",
               "架构", "世界模型", "推理", "benchmark"]
    # 科技应用
    app_kw = ["落地", "应用", "发布", "产品", "驾驶", "量产", "上线", "办公",
              "安全", "Agent", "MCP", "搜索", "助手", "浏览器", "操作系统",
              "插件", "开箱", "实测", "上手", "体验", "机器人"]
    # 科技企业
    ent_kw = ["融资", "上市", "股价", "收购", "投资", "估值", "裁员", "拆分",
              "财报", "营收", "IPO", "美元", "亿元", "万亿", "创始人", "CEO",
              "任命", "离职", "加盟", "合作", "签约", "订单"]

    if any(kw in t for kw in tech_kw):
        return "tech"
    if any(kw in t for kw in app_kw):
        return "app"
    if any(kw in t for kw in ent_kw):
        return "enterprise"
    return "tech"  # default

# ============ 各来源抓取 ============

def scrape_36kr():
    """36氪 RSS（带摘要）优先，HTML 兜底"""
    items = parse_rss(fetch("https://36kr.com/feed"), 10)
    if items:
        result = []
        ai_kw = ["AI", "智能", "模型", "芯片", "机器人", "算法", "算力",
                 "开源", "Agent", "融资", "科技", "大模型", "GPT", "Claude"]
        for i in items:
            if any(kw in i["title"] for kw in ai_kw):
                result.append({"title": i["title"], "url": i["link"],
                               "excerpt": clean_text(i["desc"]), "src": "36氪", "tag": ""})
            if len(result) >= 4:
                break
        return result
    html = fetch("https://36kr.com/information/technology/")
    if not html:
        return []
    result = []
    # 提取文章链接和标题
    for m in re.finditer(r'/"p/(\d+)"[^>]*>\s*<[^>]+>\s*<[^>]+>(.+?)</', html):
        pid = m.group(1)
        title = re.sub(r"<[^>]+>", "", m.group(2)).strip()
        if len(title) < 8:
            continue
        url = "https://36kr.com/p/" + pid
        if title not in [i["title"] for i in result]:
            result.append({"title": title, "url": url, "excerpt": "", "src": "36氪", "tag": ""})
        if len(result) >= 4:
            break
    return result

def scrape_huxiu():
    """虎嗅 RSS（带摘要）优先，HTML 兜底"""
    items = parse_rss(fetch("https://www.huxiu.com/rss/0.xml"), 8)
    if items:
        return [{"title": i["title"], "url": i["link"], "excerpt": clean_text(i["desc"]),
                 "src": "虎嗅", "tag": ""} for i in items]
    html = fetch("https://www.huxiu.com/channel/105.html")
    if not html:
        return []
    items = []
    # 匹配文章标题
    for m in re.finditer(r'href="(https://www\.huxiu\.com/article/\d+\.html)"[^>]*>\s*(?:<[^>]+>)*\s*([^<]{8,80})\s*(?:</[^>]+>)*\s*</a>', html):
        url = m.group(1)
        title = m.group(2).strip()
        if len(title) < 8:
            continue
        if title not in [i["title"] for i in items]:
            items.append({"title": title, "url": url, "excerpt": "", "src": "虎嗅", "tag": ""})
        if len(items) >= 5:
            break
    return items

def scrape_geekpark():
    """极客公园 RSS"""
    items = parse_rss(fetch("https://www.geekpark.net/rss"), 6)
    return [{"title": i["title"], "url": i["link"], "excerpt": clean_text(i["desc"]),
             "src": "极客公园", "tag": ""} for i in items]

def scrape_ithome():
    """IT之家 RSS"""
    items = parse_rss(fetch("https://www.ithome.com/rss/"), 8)
    result = []
    ai_kw = ["AI", "智能", "芯片", "机器人", "华为", "小米", "苹果", "谷歌",
             "微软", "特斯拉", "英伟达", "字节", "腾讯", "阿里", "百度",
             "OpenAI", "DeepSeek", "鸿蒙", "手机", "电脑", "GPU", "CPU"]
    for i in items:
        if any(kw in i["title"] for kw in ai_kw):
            result.append({"title": i["title"], "url": i["link"], "excerpt": clean_text(i["desc"]),
                          "src": "IT之家", "tag": ""})
        if len(result) >= 4:
            break
    return result

def scrape_qbitai():
    """量子位 RSS（WordPress feed，带摘要）优先，HTML 兜底"""
    items = parse_rss(fetch("https://www.qbitai.com/feed"), 8)
    if items:
        return [{"title": i["title"], "url": i["link"], "excerpt": clean_text(i["desc"]),
                 "src": "量子位", "tag": ""} for i in items]
    html = fetch("https://www.qbitai.com/category/%e8%b5%84%e8%ae%af")
    if not html:
        return []
    items = []
    # 匹配文章链接
    for m in re.finditer(r'<a[^>]*href="(https://www\.qbitai\.com/\d{4}/\d{2}/\d+\.html)"[^>]*>(.+?)</a>', html):
        url, raw = m.group(1), m.group(2)
        title = re.sub(r"<[^>]+>", "", raw).strip()
        # 跳过导航链接
        if len(title) < 10 or "下一页" in title or "上一页" in title:
            continue
        if title not in [i["title"] for i in items]:
            items.append({"title": title, "url": url, "excerpt": "",
                         "src": "量子位", "tag": ""})
        if len(items) >= 5:
            break
    return items

def scrape_jiqizhixin():
    """机器之心 RSS"""
    items = parse_rss(fetch("https://www.jiqizhixin.com/rss"), 5)
    return [{"title": i["title"], "url": i["link"], "excerpt": clean_text(i["desc"]),
             "src": "机器之心", "tag": ""} for i in items]

def scrape_xinzhiyuan():
    """新智元首页 HTML + 详情页 og:description 摘要增强"""
    html = fetch("https://aiera.com.cn/")
    if not html:
        return []
    items = []
    # 从首页提取文章链接和标题
    for m in re.finditer(r'<a[^>]*href="(https://aiera\.com\.cn/\d{4}/\d{2}/\d{2}/[^"]+)"[^>]*>\s*(.+?)\s*</a>', html, re.DOTALL):
        url, raw = m.group(1), m.group(2)
        title = re.sub(r"<[^>]+>", "", raw).strip()
        if len(title) < 10 or "上一页" in title or "下一页" in title:
            continue
        if title not in [i["title"] for i in items]:
            items.append({"title": title, "url": url, "excerpt": "", "src": "新智元", "tag": ""})
        if len(items) >= 5:
            break
    # 对前几条抓详情页 og:description 作为摘要
    for i in items[:4]:
        if not i["excerpt"]:
            page = fetch(i["url"])
            if page:
                m = re.search(r'<meta[^>]+(?:name|property)=["\'](?:og:description|description)["\'][^>]+content=["\']([^"\']+)["\']', page)
                if not m:
                    m = re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:name|property)=["\'](?:og:description|description)["\']', page)
                if m:
                    i["excerpt"] = clean_text(m.group(1))
    return items

# ============ 主流程 ============

def main():
    print("=" * 50)
    print(f"每日一报 · 新闻抓取 — {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print("=" * 50)

    all_articles = []

    scrapers = [
        ("虎嗅", scrape_huxiu),
        ("36氪", scrape_36kr),
        ("机器之心", scrape_jiqizhixin),
        ("极客公园", scrape_geekpark),
        ("量子位", scrape_qbitai),
        ("新智元", scrape_xinzhiyuan),
        ("IT之家", scrape_ithome),
    ]

    for name, func in scrapers:
        print(f"\n📡 抓取 {name}...")
        try:
            articles = func()
            print(f"   ✅ 获取 {len(articles)} 条")
            for a in articles:
                cat = tech_keywords(a["title"])
                all_articles.append({
                    "title": a["title"],
                    "url": a["url"],
                    "excerpt": a.get("excerpt", ""),
                    "src": a["src"],
                    "tag": extract_tag(a["title"]),  # 从标题提取真实领域标签
                    "cat": cat,
                })
        except Exception as e:
            print(f"   ❌ 出错: {e}")

    # 按分类组织
    tech_news = [a for a in all_articles if a["cat"] == "tech"][:8]
    app_news = [a for a in all_articles if a["cat"] == "app"][:6]
    ent_news = [a for a in all_articles if a["cat"] == "enterprise"][:8]

    # 填充不足的分类
    remaining = [a for a in all_articles if a not in tech_news + app_news + ent_news]
    while len(tech_news) < 4 and remaining:
        tech_news.append(remaining.pop(0))
    while len(app_news) < 4 and remaining:
        app_news.append(remaining.pop(0))
    while len(ent_news) < 4 and remaining:
        ent_news.append(remaining.pop(0))

    # 今日速览关键词总结（每类 Top 关键词）+ 全站热词（随新闻自动更新）
    all_titles = [a["title"] for a in all_articles]
    overview = {
        "tech": extract_keywords([a["title"] for a in tech_news], 6),
        "app": extract_keywords([a["title"] for a in app_news], 6),
        "enterprise": extract_keywords([a["title"] for a in ent_news], 6),
    }
    hotwords = build_hotwords(all_titles, 20)

    data = {
        "date": datetime.now().strftime("%Y-%m-%d"),
        "tech": tech_news,
        "app": app_news,
        "enterprise": ent_news,
        "overview": overview,
        "hotwords": hotwords,
        "total": len(tech_news) + len(app_news) + len(ent_news)
    }

    # 写入 news-data.js
    js_content = "window.__NEWS_DATA__ = " + json.dumps(data, ensure_ascii=False, indent=2) + ";"
    with open("news-data.js", "w", encoding="utf-8") as f:
        f.write(js_content)

    # 更新 index.html 中的新闻版本参数（URL 变化 → 浏览器/CDN 缓存失效 → 展示最新数据）
    # news-data.js 响应头带 max-age=31536000,immutable，必须用带版本参数的 URL 绕过缓存
    today = datetime.now().strftime("%Y%m%d")
    html_path = "index.html"
    try:
        with open(html_path, encoding="utf-8") as f:
            html = f.read()
        new_html = re.sub(r'news-data\.js\?v=[A-Za-z0-9_]*', f'news-data.js?v={today}', html)
        if new_html != html:
            with open(html_path, "w", encoding="utf-8") as f:
                f.write(new_html)
            print(f"   已更新: index.html (news-data.js?v={today})")
    except Exception as e:
        print(f"   ⚠️ 更新 index.html 失败: {e}")

    print(f"\n{'='*50}")
    print(f"✅ 完成！共 {data['total']} 条新闻")
    print(f"   前沿科技: {len(tech_news)} | 科技应用: {len(app_news)} | 科技企业: {len(ent_news)}")
    print(f"   已写入: news-data.js")
    print(f"{'='*50}")

if __name__ == "__main__":
    main()
