"""
每日一报 · 融资事件自动抓取脚本
由 GitHub Actions 每小时执行
首次：抓取 pitchhub.36kr.com 全部融资事件
后续：仅抓取新增融资事件（对比已存在 ID）
"""
import os, re, json, sys, time, urllib.request
from datetime import datetime

os.environ['PLAYWRIGHT_BROWSERS_PATH'] = os.environ.get('PLAYWRIGHT_BROWSERS_PATH', '')
from playwright.sync_api import sync_playwright

DATA_FILE = 'financing-data.js'
SALT = '__sth_you_should_not_know__'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

CITY2PROV = {
    '深圳':'广东省','广州':'广东省','珠海':'广东省','佛山':'广东省','东莞':'广东省','惠州':'广东省','中山':'广东省','江门':'广东省',
    '杭州':'浙江省','宁波':'浙江省','温州':'浙江省','嘉兴':'浙江省','绍兴':'浙江省','金华':'浙江省','湖州':'浙江省','台州':'浙江省',
    '南京':'江苏省','苏州':'江苏省','无锡':'江苏省','常州':'江苏省','南通':'江苏省','徐州':'江苏省','扬州':'江苏省','镇江':'江苏省','泰州':'江苏省',
    '合肥':'安徽省','芜湖':'安徽省','马鞍山':'安徽省','蚌埠':'安徽省',
    '武汉':'湖北省','宜昌':'湖北省','襄阳':'湖北省',
    '成都':'四川省','绵阳':'四川省','德阳':'四川省',
    '西安':'陕西省','咸阳':'陕西省','宝鸡':'陕西省',
    '沈阳':'辽宁省','大连':'辽宁省',
    '长春':'吉林省','吉林':'吉林省',
    '哈尔滨':'黑龙江省','大庆':'黑龙江省',
    '福州':'福建省','厦门':'福建省','泉州':'福建省','漳州':'福建省',
    '昆明':'云南省','贵阳':'贵州省','南昌':'江西省','太原':'山西省',
    '石家庄':'河北省','唐山':'河北省','保定':'河北省',
    '郑州':'河南省','洛阳':'河南省',
    '长沙':'湖南省','株洲':'湖南省','湘潭':'湖南省',
    '青岛':'山东省','济南':'山东省','烟台':'山东省','潍坊':'山东省','淄博':'山东省',
    '兰州':'甘肃省','银川':'宁夏回族自治区','西宁':'青海省',
    '呼和浩特':'内蒙古自治区','包头':'内蒙古自治区','鄂尔多斯':'内蒙古自治区',
    '乌鲁木齐':'新疆维吾尔自治区','海口':'海南省','南宁':'广西壮族自治区','桂林':'广西壮族自治区',
    '拉萨':'西藏自治区',
}

def clean_prov(raw):
    if not raw:
        return '中国'
    s = re.sub(r'^(位于|是|于|在)', '', str(raw)).strip()
    for p in ['北京市','上海市','天津市','重庆市','广西壮族自治区','内蒙古自治区','宁夏回族自治区','新疆维吾尔自治区','西藏自治区']:
        if p in s:
            return p
    m = re.search(r'([\u4e00-\u9fa5]{2}省)', s)
    if m:
        return m.group(1)
    for city, prov in CITY2PROV.items():
        if city in s:
            return prov
    return '中国'

def fetch_province(request_ctx, project_id):
    """从项目详情页提取省份（用 Playwright request，复用浏览器网络栈）"""
    try:
        resp = request_ctx.get(f'https://pitchhub.36kr.com/project/{project_id}',
                               headers={'User-Agent': UA}, timeout=15000)
        html = resp.text()
        for mm in re.finditer(r'([\u4e00-\u9fa5]{2,4}?(?:省|市|自治区|特别行政区))', html):
            p = mm.group(1)
            if p and len(p) <= 8 and not re.search(r'(消费|行业|已上市|补油|产品|大众)', p):
                return clean_prov(p)
    except Exception:
        pass
    return '中国'

def load_existing():
    """读取已有 financing-data.js，返回 (list, id_set)"""
    if not os.path.exists(DATA_FILE):
        return [], set()
    try:
        with open(DATA_FILE, encoding='utf-8') as f:
            content = f.read()
        ns = {'__FINANCING_DATA__': None}
        exec(content.replace('window.__FINANCING_DATA__', '__FINANCING_DATA__'), ns)
        data = ns.get('__FINANCING_DATA__') or {}
        lst = data.get('list', [])
        ids = {it.get('id') for it in lst}
        return lst, ids
    except Exception as e:
        print(f'  ⚠ 读取已有数据失败: {e}')
        return [], set()

def extract_items(page, page_no):
    """从渲染后的页面提取融资事件条目"""
    rows = page.query_selector_all('div.table-row.table-row-body')
    items = []
    for row in rows:
        try:
            link_el = row.query_selector('a.project-info')
            if not link_el:
                continue
            href = link_el.get_attribute('href') or ''
            m = re.search(r'/project/(\d+)', href)
            if not m:
                continue
            pid = m.group(1)
            name_el = row.query_selector('.projectName')
            brief_el = row.query_selector('.projectBrief')
            date_el = row.query_selector('.financingDate-content')
            ind_el = row.query_selector('.industryList-content')
            round_el = row.query_selector('.financingRoundRemark-content')
            money_el = row.query_selector('.financingMoney-content')
            inv_el = row.query_selector('.investor-content')
            name = name_el.inner_text().strip() if name_el else ''
            brief = brief_el.inner_text().strip() if brief_el else ''
            date = date_el.inner_text().strip() if date_el else ''
            industry = ind_el.inner_text().replace('\u00a0', ' ') if ind_el else ''
            industry = [i.strip() for i in industry.split() if i.strip()]
            rnd = round_el.inner_text().strip() if round_el else ''
            money = money_el.inner_text().strip() if money_el else ''
            inv = inv_el.inner_text().strip() if inv_el else ''
            if not name or not pid:
                continue
            items.append({
                'id': pid, 'date': date, 'name': name, 'desc': brief,
                'industry': [i for i in industry.replace('\u00a0', '').split() if i],
                'round': rnd, 'amount': money, 'investors': inv,
                'url': f'https://pitchhub.36kr.com/project/{pid}',
                'province': '中国',  # 稍后补
            })
        except Exception as e:
            print(f'  ⚠ 条目解析失败: {e}')
    return items

def main():
    print('=' * 50)
    print(f'融资事件抓取 — {datetime.now().strftime("%Y-%m-%d %H:%M")}')
    print('=' * 50)

    existing, existing_ids = load_existing()
    print(f'已有数据: {len(existing)} 条')

    mode = '增量' if existing_ids else '全量'
    print(f'模式: {mode}')

    new_items = []
    page_no = 1
    empty_pages = 0
    seen_ids = set()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(user_agent=UA, viewport={'width': 1280, 'height': 900})
        while True:
            url = f'https://pitchhub.36kr.com/investevent?pageSize=20&pageNo={page_no}'
            print(f'\n📄 抓取第 {page_no} 页...')
            try:
                page.goto(url, timeout=45000, wait_until='networkidle')
                page.wait_for_timeout(2500)
                items = extract_items(page, page_no)
            except Exception as e:
                print(f'  ❌ 页面加载失败: {e}')
                break

            if not items:
                empty_pages += 1
                if empty_pages >= 2:
                    print('  连续无数据，停止翻页')
                    break
                page_no += 1
                continue
            empty_pages = 0

            # 判断停止条件
            all_exist = all(it['id'] in existing_ids for it in items)
            fresh_ids = [it['id'] for it in items if it['id'] not in existing_ids and it['id'] not in seen_ids]
            print(f'  本页 {len(items)} 条，新增 {len(fresh_ids)} 条')
            if all_exist and existing_ids:
                print('  本页全部已存在，停止（增量完成）')
                break
            if mode == '全量' and len(items) < 20:
                print('  全量抓取到达末页')
                for it in items:
                    if it['id'] not in seen_ids:
                        new_items.append(it)
                        seen_ids.add(it['id'])
                break

            for it in items:
                if it['id'] not in existing_ids and it['id'] not in seen_ids:
                    new_items.append(it)
                    seen_ids.add(it['id'])
            page_no += 1
            if page_no > 200:
                print('  超过 200 页，停止')
                break
        browser.close()

    print(f'\n本次新增: {len(new_items)} 条')

    if not new_items:
        print('无新增，数据未变化')
        return

    # 补省份（Playwright request 抓详情页）
    print('抓取省份信息...')
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(user_agent=UA)
        req_ctx = ctx.request
        for i, it in enumerate(new_items):
            it['province'] = fetch_province(req_ctx, it['id'])
            if (i + 1) % 10 == 0:
                print(f'  {i+1}/{len(new_items)}')
        browser.close()

    # 合并数据
    all_items = new_items + [it for it in existing if it['id'] not in {n['id'] for n in new_items}]
    all_items.sort(key=lambda x: x['date'], reverse=True)

    # 重建统计
    inds = sorted({i for it in all_items for i in it.get('industry', [])})
    rounds = sorted({it['round'] for it in all_items if it.get('round')})
    provs = sorted({it['province'] for it in all_items})

    data = {
        'updated': datetime.now().strftime('%Y-%m-%d %H:%M'),
        'total': len(all_items),
        'industries': inds,
        'rounds': rounds,
        'provinces': provs,
        'list': all_items,
    }
    js = 'window.__FINANCING_DATA__ = ' + json.dumps(data, ensure_ascii=False, indent=1) + ';'
    with open(DATA_FILE, 'w', encoding='utf-8') as f:
        f.write(js)

    print(f'\n✅ 完成！共 {len(all_items)} 条（新增 {len(new_items)}）')
    print(f'   已写入 {DATA_FILE}')

if __name__ == '__main__':
    main()
