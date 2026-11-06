//! kiplay_hasher - hash teks atau berkas. Algoritma: sha256, sha512, sha1.

use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};
use std::env;
use std::fs;
use std::io::{self, Read};
use std::process::ExitCode;

fn hash_bytes(alg: &str, data: &[u8]) -> String {
    match alg {
        "sha1" => hex::encode(Sha1::digest(data)),
        "sha512" => hex::encode(Sha512::digest(data)),
        _ => hex::encode(Sha256::digest(data)),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();

    if args.len() < 3 {
        eprintln!("pemakaian: kiplay_hasher <sha256|sha512|sha1> <teks|->");
        eprintln!("  '-' artinya baca dari stdin");
        return ExitCode::FAILURE;
    }

    let alg = args[1].to_lowercase();
    if !["sha1", "sha256", "sha512"].contains(&alg.as_str()) {
        eprintln!("algoritma tidak didukung: {alg}");
        return ExitCode::FAILURE;
    }

    let data: Vec<u8> = if args[2] == "-" {
        let mut buf = Vec::new();
        if let Err(e) = io::stdin().read_to_end(&mut buf) {
            eprintln!("gagal baca stdin: {e}");
            return ExitCode::FAILURE;
        }
        buf
    } else if let Ok(isi) = fs::read(&args[2]) {
        isi
    } else {
        args[2].as_bytes().to_vec()
    };

    println!("{}", hash_bytes(&alg, &data));
    ExitCode::SUCCESS
}
