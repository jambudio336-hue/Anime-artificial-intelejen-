// KiplayClip.cs - hash & generator sandi.
// build: dotnet run --project KiplayClip.csproj -- sha256 "teks"

using System;
using System.Security.Cryptography;
using System.Text;

namespace Kiplay;

internal static class Program
{
    private const string Pemakaian = "pemakaian: KiplayClip <sha256|sha512|md5|sandi> <teks|panjang>";

    private static string Hash(string algoritma, string teks)
    {
        byte[] data = Encoding.UTF8.GetBytes(teks);
        return algoritma.ToLowerInvariant() switch
        {
            "md5" => Convert.ToHexString(MD5.HashData(data)).ToLowerInvariant(),
            "sha512" => Convert.ToHexString(SHA512.HashData(data)).ToLowerInvariant(),
            _ => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant(),
        };
    }

    private static string AcakSandi(int panjang)
    {
        const string abjad = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*";
        var sb = new StringBuilder(panjang);
        Span<byte> buf = stackalloc byte[1];
        for (int i = 0; i < panjang; i++)
        {
            RandomNumberGenerator.Fill(buf);
            sb.Append(abjad[buf[0] % abjad.Length]);
        }
        return sb.ToString();
    }

    private static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine(Pemakaian);
            return 1;
        }

        if (args[0].Equals("sandi", StringComparison.OrdinalIgnoreCase))
        {
            int n = int.TryParse(args[1], out var p) ? p : 24;
            Console.WriteLine(AcakSandi(Math.Clamp(n, 8, 128)));
            return 0;
        }

        Console.WriteLine(Hash(args[0], args[1]));
        return 0;
    }
}
