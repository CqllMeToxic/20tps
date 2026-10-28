using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using Realms;

// usage: LazerIndex <osu!lazer install dir> <client.realm> <output.json>
if (args.Length != 3)
{
    Console.Error.WriteLine("usage: LazerIndex <lazer install dir> <client.realm> <output.json>");
    return 2;
}

string installDir = args[0];

// load realm (and the stuff it depends on) from the lazer install instead of shipping our own copy.
AppDomain.CurrentDomain.AssemblyResolve += (_, e) =>
{
    string path = Path.Combine(installDir, new AssemblyName(e.Name).Name + ".dll");
    return File.Exists(path) ? Assembly.LoadFrom(path) : null;
};

return Indexer.Run(installDir, args[1], args[2]);

static class Indexer
{
    public static int Run(string installDir, string realmPath, string outputPath)
    {
        // realm's native library lives in the lazer install too.
        NativeLibrary.SetDllImportResolver(typeof(Realm).Assembly, (name, _, _) =>
        {
            string path = Path.Combine(installDir, name.EndsWith(".dll") ? name : name + ".dll");
            return File.Exists(path) ? NativeLibrary.Load(path) : IntPtr.Zero;
        });

        var config = new RealmConfiguration(realmPath) { IsDynamic = true, IsReadOnly = true };
        using var realm = Realm.GetInstance(config);
        if (Environment.GetEnvironmentVariable("LAZER_INDEX_DEBUG") != null)
            foreach (var c in realm.Schema) Console.WriteLine("schema: " + c.Name + ": " + string.Join(", ", c.Select(p => p.Name + ":" + p.Type + (p.ObjectType != null ? "<" + p.ObjectType + ">" : ""))));

        var sets = new List<object>();
        foreach (var set in realm.DynamicApi.All("BeatmapSet"))
        {
            if (set.DynamicApi.Get<bool>("DeletePending")) continue;
            var files = Files(set);
            if (files.Count == 0) continue;
            sets.Add(new
            {
                id = set.DynamicApi.Get<Guid>("ID").ToString(),
                onlineId = set.DynamicApi.Get<int>("OnlineID"),
                files,
            });
        }

        var skins = new List<object>();
        foreach (var skin in realm.DynamicApi.All("Skin"))
        {
            if (skin.DynamicApi.Get<bool>("DeletePending")) continue;
            var files = Files(skin);
            if (files.Count == 0) continue; // built-in skins don't have any files
            skins.Add(new
            {
                id = skin.DynamicApi.Get<Guid>("ID").ToString(),
                name = skin.DynamicApi.Get<string>("Name"),
                creator = skin.DynamicApi.Get<string>("Creator"),
                files,
            });
        }

        var index = new { beatmapSets = sets, skins };
        string tmp = outputPath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(index));
        File.Move(tmp, outputPath, true);
        Console.WriteLine($"{sets.Count} beatmap sets, {skins.Count} skins");
        return 0;
    }

    /** file name -> content hash, for an object's Files list. */
    static Dictionary<string, string> Files(IRealmObjectBase owner)
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var usage in owner.DynamicApi.GetList<IEmbeddedObject>("Files"))
        {
            var file = usage.DynamicApi.Get<IRealmObjectBase>("File");
            string name = usage.DynamicApi.Get<string>("Filename");
            if (file == null || string.IsNullOrEmpty(name)) continue;
            map[name] = file.DynamicApi.Get<string>("Hash");
        }
        return map;
    }
}
