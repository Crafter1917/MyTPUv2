расписание:
1 - добавить добавили
2 - отображается коректно
3 нужно сделать не только на текущую неделю
4 нужно сделать не только нашу группу
5 допилить.....
мудл:
1 адаптируем кнопочки с хтмл страници.
2 
3 добавляем нью активити для каждой ссылки (курсы, задания, тесты и т.д.).
4 попытка сделать всё как в реадми, но к сожалению почему то jsoup не хочет находить нужный элемент хз шо с этим делать. надо будет переделать потом с помощью curl запроса:
import requests

cookies = {
'auth_ldaposso_authprovider': 'NOOSSO',
'MoodleSession': 'rmpf5vvcegmader7o0d8kuq9qi',
}

headers = {
'Accept': 'application/json, text/javascript, */*; q=0.01',
'Accept-Language': 'ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,zh-TW;q=0.6,zh;q=0.5',
'Connection': 'keep-alive',
'Content-Type': 'application/json',
# 'Cookie': 'auth_ldaposso_authprovider=NOOSSO; MoodleSession=rmpf5vvcegmader7o0d8kuq9qi',
'DNT': '1',
'Origin': 'https://stud.lms.tpu.ru',
'Referer': 'https://stud.lms.tpu.ru/my/',
'Sec-Fetch-Dest': 'empty',
'Sec-Fetch-Mode': 'cors',
'Sec-Fetch-Site': 'same-origin',
'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
'X-Requested-With': 'XMLHttpRequest',
'sec-ch-ua': '"Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"',
'sec-ch-ua-mobile': '?0',
'sec-ch-ua-platform': '"Windows"',
}

params = {
'sesskey': '3AHkg2y04k',
'info': 'core_course_get_enrolled_courses_by_timeline_classification',
}

json_data = [
{
'index': 0,
'methodname': 'core_course_get_enrolled_courses_by_timeline_classification',
'args': {
'offset': 0,
'limit': 0,
'classification': 'all',
'sort': 'ul.timeaccess desc',
'customfieldname': '',
'customfieldvalue': '',
},
},
]

response = requests.post(
'https://stud.lms.tpu.ru/lib/ajax/service.php',
params=params,
cookies=cookies,
headers=headers,
json=json_data,
)

# Note: json_data will not be serialized by requests
# exactly as it was in the original request.
#data = '[{"index":0,"methodname":"core_course_get_enrolled_courses_by_timeline_classification","args":{"offset":0,"limit":0,"classification":"all","sort":"ul.timeaccess desc","customfieldname":"","customfieldvalue":""}}]'
#response = requests.post('https://stud.lms.tpu.ru/lib/ajax/service.php', params=params, cookies=cookies, headers=headers, data=data)
