<?php

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;

/*
 * Endpoint tipis buat dipanggil dari Agent Mas Kiplay.
 * Semua route di sini otomatis berprefix /api.
 */

Route::get('/health', fn () => response()->json([
    'ok'    => true,
    'nama'  => 'Mas Kiplay API',
    'ver'   => '6.0.0',
    'waktu' => now()->toIso8601String(),
]));

Route::post('/hash', function (Request $r) {
    $data = $r->validate([
        'text' => 'required|string|max:5000',
        'alg'  => 'nullable|in:sha1,sha256,sha512,md5',
    ]);

    return response()->json([
        'alg'   => $data['alg'] ?? 'sha256',
        'hasil' => hash($data['alg'] ?? 'sha256', $data['text']),
    ]);
});

Route::post('/scan', function (Request $r) {
    $data = $r->validate([
        'host'    => ['required', 'string', 'max:253', 'regex:/^[a-zA-Z0-9.\-]+$/'],
        'port'    => 'required|integer|between:1,65535',
        'timeout' => 'nullable|numeric|between:0.1,5',
    ]);

    $timeout = (float) ($data['timeout'] ?? 1.0);
    $mulai = microtime(true);
    $fp = @fsockopen($data['host'], $data['port'], $kode, $pesan, $timeout);
    $durasi = (int) round((microtime(true) - $mulai) * 1000);

    if ($fp) {
        fclose($fp);
    }

    return response()->json([
        'host'  => $data['host'],
        'port'  => $data['port'],
        'buka'  => (bool) $fp,
        'ms'    => $durasi,
        'error' => $fp ? null : ($pesan ?: 'tertutup'),
    ]);
});

Route::post('/banner', function (Request $r) {
    $data = $r->validate([
        'host' => 'required|string|max:253',
        'port' => 'required|integer|between:1,65535',
    ]);

    $fp = @fsockopen($data['host'], $data['port'], $kode, $pesan, 2.0);

    if (! $fp) {
        return response()->json(['banner' => null, 'error' => $pesan ?: 'gagal']);
    }

    stream_set_timeout($fp, 2);
    fwrite($fp, "HEAD / HTTP/1.0\r\nHost: {$data['host']}\r\n\r\n");
    $baris = fgets($fp, 512);
    fclose($fp);

    return response()->json([
        'banner' => $baris ? trim($baris) : null,
        'error'  => null,
    ]);
});

Route::fallback(fn () => response()->json([
    'ok'    => false,
    'pesan' => 'route tidak ada',
], 404));
