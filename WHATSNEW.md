[en]
Films and episodes are now fetched a different way on Windows.

Some episodes arrive with their sound stored at the far end of the file, away
from the picture. Playing a second of such a file means jumping across two
gigabytes and back, and the player was opening a fresh connection for every
jump - a fifth of a second each, five times a second. Those files now go
through FFmpeg's own web code, which keeps one connection open across the
jumps. Live channels are untouched.

A file made this way is hard on any player; the lasting fix is for it to be
stored with its sound and picture together.

[ku]
فیلم و بەشەکانی سریاڵ ئێستا بە شێوەیەکی تر دەهێنرێن لەسەر Windows.

هەندێک بەش دەنگەکەیان لە کۆتایی فایلەکەدا هەڵگیراوە، دوور لە وێنەکە. بۆ
پەخشکردنی یەک چرکە لە فایلێکی وا، پێویستە دوو گیگابایت بازبدرێت و بگەڕێتەوە،
و پلەیەرەکە بۆ هەر بازێک پەیوەندییەکی نوێی دەکردەوە — پێنج یەکی چرکە بۆ هەر
یەکێکیان، پێنج جار لە چرکەیەکدا. ئەو فایلانە ئێستا بە کۆدی وێبی خودی FFmpeg
دەهێنرێن، کە یەک پەیوەندی بە درێژایی بازەکان کراوە ڕادەگرێت. کەناڵە
زیندووەکان دەستیان لێ نەدراوە.

فایلێکی بەم شێوەیە بۆ هەر پلەیەرێک قورسە؛ چارەسەری هەمیشەیی ئەوەیە کە
دەنگ و وێنەی پێکەوە هەڵبگیرێت.

[ar]
الأفلام والحلقات تُجلب الآن بطريقة أخرى على Windows.

بعض الحلقات يُخزَّن صوتها في آخر الملف، بعيدًا عن الصورة. تشغيل ثانية واحدة من
ملف كهذا يعني القفز عبر غيغابايتين ذهابًا وإيابًا، وكان المشغّل يفتح اتصالًا
جديدًا لكل قفزة — خُمس ثانية لكل واحدة، خمس مرات في الثانية. صارت هذه الملفات
تُجلب عبر شيفرة الويب الخاصة بـ FFmpeg، التي تبقي اتصالًا واحدًا مفتوحًا عبر
القفزات. القنوات المباشرة لم تُمس.

ملف بهذه الصيغة ثقيل على أي مشغّل؛ والإصلاح الدائم أن يُخزَّن صوته وصورته معًا.
