use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

#[derive(Clone)]
pub struct Config {
    pub values: HashMap<String, String>,
    pub path: Option<PathBuf>,
}

fn parse_package_from_apk_path(mapped_name: &str) -> Option<String> {
    let apk = mapped_name.split("!/").next().unwrap_or("");
    for seg in apk.split('/') {
        if seg.contains('.') && seg.contains('-') && !seg.starts_with("~~") {
            if let Some((pkg, _)) = seg.split_once('-') {
                if !pkg.is_empty() && pkg.contains('.') {
                    return Some(pkg.to_string());
                }
            }
        }
    }
    None
}

pub fn package() -> String {
    if let Some(m) = crate::maps::find_module("libcinder_mobile.so") {
        if let Some(pkg) = parse_package_from_apk_path(&m.name) {
            return pkg;
        }
    }
    std::fs::read("/proc/self/cmdline")
        .ok()
        .and_then(|b| b.split(|c| *c == 0).next().map(|s| s.to_vec()))
        .and_then(|s| String::from_utf8(s).ok())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "com.discord.release".into())
}

fn parse_config_text(text: &str) -> HashMap<String, String> {
    let mut values = HashMap::new();
    for raw_line in text.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, raw_v)) = line.split_once('=') else {
            continue;
        };
        let key = k.trim();
        if key.is_empty() {
            continue;
        }
        let mut val = raw_v.trim();

        if let Some(q) = val.bytes().next().filter(|&b| b == b'"' || b == b'\'') {
            let rest = &val[1..];
            if let Some(end) = rest.find(q as char) {
                val = &rest[..end];
            }
        } else if let Some((before_comment, _)) = val.split_once('#') {
            val = before_comment.trim();
        }
        values.insert(key.to_string(), val.to_string());
    }
    values
}

impl Config {
    pub fn load() -> Self {
        let mut candidates = Vec::new();
        if let Ok(p) = std::env::var("CINDER_CONFIG") {
            candidates.push(PathBuf::from(p));
        }
        let pkg = package();
        candidates.push(PathBuf::from(format!(
            "/data/data/{pkg}/files/cinder/cinder.toml"
        )));
        candidates.push(PathBuf::from(format!(
            "/sdcard/Android/data/{pkg}/files/cinder/cinder.toml"
        )));

        for known in ["com.discord.release", "com.discord.release.cinder"] {
            candidates.push(PathBuf::from(format!(
                "/sdcard/Android/data/{known}/files/cinder/cinder.toml"
            )));
        }
        for fallback in ["com.discord.release", "com.discord.release.cinder"] {
            candidates.push(PathBuf::from(format!(
                "/data/data/{fallback}/files/cinder/cinder.toml"
            )));
        }
        for path in candidates {
            if let Ok(text) = fs::read_to_string(&path) {
                let values = parse_config_text(&text);
                return Config {
                    values,
                    path: Some(path),
                };
            }
        }
        Config {
            values: HashMap::new(),
            path: None,
        }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.values.get(key).map(|v| v.as_str())
    }

    pub fn bool(&self, key: &str, default: bool) -> bool {
        match self.get(key) {
            Some("true") | Some("1") | Some("yes") => true,
            Some("false") | Some("0") | Some("no") => false,
            _ => default,
        }
    }

    pub fn home(&self) -> PathBuf {
        if let Some(h) = self.get("home") {
            return PathBuf::from(h);
        }
        self.path
            .as_ref()
            .and_then(|p| p.parent())
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| PathBuf::from(format!("/data/data/{}/files/cinder", package())))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apk_path_handles_hyphens_in_base64url_suffix() {
        let p = "/data/app/~~aB-12==/com.discord.release-xY-9z_0==/base.apk!/lib/arm64-v8a/libcinder_mobile.so";
        assert_eq!(
            parse_package_from_apk_path(p).as_deref(),
            Some("com.discord.release")
        );
        let p2 = "/data/app/~~abc==/com.discord.release.cinder-A-B-C==/base.apk";
        assert_eq!(
            parse_package_from_apk_path(p2).as_deref(),
            Some("com.discord.release.cinder")
        );
    }

    #[test]
    fn config_strips_inline_comments_and_quotes() {
        let cfg = parse_config_text(
            r#"
            # full line comment
            log = "info" # trailing comment
            verify_only = true # default: true
            version_prefix = c1ndr # arms patch
            home = '/data/data/com.discord.release/files/cinder'
            "#,
        );
        assert_eq!(cfg.get("log").map(String::as_str), Some("info"));
        assert_eq!(cfg.get("verify_only").map(String::as_str), Some("true"));
        assert_eq!(cfg.get("version_prefix").map(String::as_str), Some("c1ndr"));
        assert_eq!(
            cfg.get("home").map(String::as_str),
            Some("/data/data/com.discord.release/files/cinder")
        );
    }
}
