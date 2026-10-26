import sys, os, shutil
P = "app/src/main/java/chromahub/rhythm/app/features/online/PlaylistStore.kt"
def stop(m):
    print("СТОП " + m + ". Ничего не записано."); sys.exit(1)
s = open(P, encoding="utf-8").read()
a = s.find("    private class Saved {")
b = s.find("    fun add(list: List<PlTrack>)")
if a < 0 or b < 0 or b < a: stop("не нашёл границы блока хранения")
if "JSONObject(f.readText())" in s: stop("исправление уже применено")
NEW = '''    val tracks = MutableStateFlow<List<PlTrack>>(emptyList())
    val ownCode = MutableStateFlow<String?>(null)
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "amorx_playlist.json")
        file = f
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("tracks") ?: JSONArray()
                tracks.value = (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    PlTrack(
                        x.getString("id"),
                        x.optString("title"),
                        x.optString("artist"),
                        x.optLong("durMs", 0L),
                        if (x.isNull("art")) null else x.optString("art")
                    )
                }
                ownCode.value = if (o.isNull("ownCode")) null else o.optString("ownCode")
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray(tracks.value.map {
                JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                    .put("durMs", it.durMs).put("art", it.art ?: JSONObject.NULL)
            })
            val o = JSONObject().put("tracks", arr).put("ownCode", ownCode.value ?: JSONObject.NULL)
            f.writeText(o.toString())
        }
    }

'''
bk = os.path.expanduser("~/Desktop/playlist_backup3"); os.makedirs(bk, exist_ok=True)
shutil.copy2(P, os.path.join(bk, "PlaylistStore.kt"))
open(P, "w", encoding="utf-8").write(s[:a] + NEW + s[b:])
print("Готово: хранение плейлиста переписано без Gson")
