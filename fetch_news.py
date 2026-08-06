"""
每日一报 · 新闻自动抓取脚本
由 GitHub Actions 每天 12:00 自动执行
"""
import json, re, sys, os
from datetime import datetime
from xml.etree import ElementTree as ET
import urllib.request

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
    """36氪科技频道 HTML"""
    html = fetch("https://36kr.com/information/technology/")
    if not html:
        return []
    items = []
    # 提取文章链接和标题
    for m in re.finditer(r'/"p/(\d+)"[^>]*>\s*<[^>]+>\s*<[^>]+>(.+?)</', html):
        pid = m.group(1)
        title = re.sub(r"<[^>]+>", "", m.group(2)).strip()
        if len(title) < 8:
            continue
        url = "https://36kr.com/p/" + pid
        if title not in [i["title"] for i in items]:
            items.append({"title": title, "url": url, "excerpt": "", "src": "36氪", "tag": "科技"})
        if len(items) >= 4:
            break
    # Also try finding article links in href
    if not items:
        for m in re.finditer(r'href="(/p/\d+)"', html):
            url = "https://36kr.com" + m.group(1)
            if url not in [i["url"] for i in items]:
                items.append({"title": "", "url": url, "excerpt": "", "src": "36氪", "tag": "科技"})
            if len(items) >= 4:
                break
    return items

def scrape_huxiu():
    """虎嗅前沿科技 HTML + RSS 备用"""
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
            items.append({"title": title, "url": url, "excerpt": "", "src": "虎嗅", "tag": "科技"})
        if len(items) >= 5:
            break
    return items

def scrape_geekpark():
    """极客公园 RSS"""
    items = parse_rss(fetch("https://www.geekpark.net/rss"), 6)
    return [{"title": i["title"], "url": i["link"], "excerpt": i["desc"][:160],
             "src": "极客公园", "tag": "科技"} for i in items]

def scrape_ithome():
    """IT之家 RSS"""
    items = parse_rss(fetch("https://www.ithome.com/rss/"), 8)
    result = []
    ai_kw = ["AI", "智能", "芯片", "机器人", "华为", "小米", "苹果", "谷歌",
             "微软", "特斯拉", "英伟达", "字节", "腾讯", "阿里", "百度",
             "OpenAI", "DeepSeek", "鸿蒙", "手机", "电脑", "GPU", "CPU"]
    for i in items:
        if any(kw in i["title"] for kw in ai_kw):
            result.append({"title": i["title"], "url": i["link"], "excerpt": i["desc"][:160],
                          "src": "IT之家", "tag": "科技"})
        if len(result) >= 4:
            break
    return result

def scrape_qbitai():
    """量子位 HTML"""
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
                         "src": "量子位", "tag": "科技"})
        if len(items) >= 5:
            break
    return items

def scrape_jiqizhixin():
    """机器之心 RSS"""
    items = parse_rss(fetch("https://www.jiqizhixin.com/rss"), 5)
    return [{"title": i["title"], "url": i["link"], "excerpt": i["desc"][:160],
             "src": "机器之心", "tag": "科技"} for i in items]

def scrape_xinzhiyuan():
    """新智元 RSS（地址不固定，尝试常见模式）"""
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
            items.append({"title": title, "url": url, "excerpt": "",
                         "src": "新智元", "tag": "科技"})
        if len(items) >= 4:
            break
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
                    "tag": a.get("tag", ""),
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

    data = {
        "date": datetime.now().strftime("%Y-%m-%d"),
        "tech": tech_news,
        "app": app_news,
        "enterprise": ent_news,
        "total": len(tech_news) + len(app_news) + len(ent_news)
    }

    # 写入 news-data.js
    js_content = "window.__NEWS_DATA__ = " + json.dumps(data, ensure_ascii=False, indent=2) + ";"
    with open("news-data.js", "w", encoding="utf-8") as f:
        f.write(js_content)

    print(f"\n{'='*50}")
    print(f"✅ 完成！共 {data['total']} 条新闻")
    print(f"   前沿科技: {len(tech_news)} | 科技应用: {len(app_news)} | 科技企业: {len(ent_news)}")
    print(f"   已写入: news-data.js")
    print(f"{'='*50}")

if __name__ == "__main__":
    main()
