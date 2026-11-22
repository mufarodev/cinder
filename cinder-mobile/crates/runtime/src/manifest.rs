use crate::config::Config;
use crate::maps;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

pub const APK_MANIFEST_ENTRY: &str = "assets/cinder-patches.json";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatchEntry {
    pub tag: String,
    pub file_offset: u64,
    pub orig: Vec<u8>,
    pub patched: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatchManifest {
    pub build_id: String,
    pub patches: Vec<PatchEntry>,
}

pub fn load(cfg: &Config) -> Option<(PatchManifest, String)> {
    if let Ok(p) = std::env::var("CINDER_PATCH_MANIFEST") {
        if let Ok(text) = fs::read_to_string(&p) {
            if let Ok(m) = parse_manifest_json(&text) {
                return Some((m, p));
            }
        }
    }

    let home_path = cfg.home().join("cinder-patches.json");
    if let Ok(text) = fs::read_to_string(&home_path) {
        if let Ok(m) = parse_manifest_json(&text) {
            return Some((m, home_path.display().to_string()));
        }
    }

    if let Some(apk_path) = maps::find_apk_path() {
        if let Some(bytes) = read_zip_stored_entry(Path::new(&apk_path), APK_MANIFEST_ENTRY) {
            if let Ok(text) = String::from_utf8(bytes) {
                if let Ok(m) = parse_manifest_json(&text) {
                    return Some((m, format!("{apk_path}!/{APK_MANIFEST_ENTRY}")));
                }
            }
        }
    }

    None
}

pub fn read_zip_stored_entry(zip_path: &Path, entry_name: &str) -> Option<Vec<u8>> {
    let mut f = File::open(zip_path).ok()?;
    let file_len = f.metadata().ok()?.len();
    if file_len < 22 {
        return None;
    }

    let tail_len = file_len.min(65557) as usize;
    f.seek(SeekFrom::End(-(tail_len as i64))).ok()?;
    let mut tail = vec![0u8; tail_len];
    f.read_exact(&mut tail).ok()?;

    let mut eocd_pos = None;
    for i in (0..=tail_len.saturating_sub(22)).rev() {
        if tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06 {
            let comment_len = u16::from_le_bytes([tail[i + 20], tail[i + 21]]) as usize;
            if i + 22 + comment_len == tail_len {
                eocd_pos = Some(i);
                break;
            }
        }
    }
    let eocd = eocd_pos?;
    let cd_size = u32::from_le_bytes([
        tail[eocd + 12],
        tail[eocd + 13],
        tail[eocd + 14],
        tail[eocd + 15],
    ]) as u64;
    let cd_off = u32::from_le_bytes([
        tail[eocd + 16],
        tail[eocd + 17],
        tail[eocd + 18],
        tail[eocd + 19],
    ]) as u64;

    if cd_off.checked_add(cd_size)? > file_len || cd_size > (64 << 20) {
        return None;
    }
    f.seek(SeekFrom::Start(cd_off)).ok()?;
    let mut cd = vec![0u8; cd_size as usize];
    f.read_exact(&mut cd).ok()?;

    let needle = entry_name.as_bytes();
    let mut pos = 0usize;
    while pos + 46 <= cd.len() {
        if cd[pos..pos + 4] != [0x50, 0x4b, 0x01, 0x02] {
            break;
        }
        let method = u16::from_le_bytes([cd[pos + 10], cd[pos + 11]]);
        let uncomp_size =
            u32::from_le_bytes([cd[pos + 24], cd[pos + 25], cd[pos + 26], cd[pos + 27]]) as usize;
        let name_len = u16::from_le_bytes([cd[pos + 28], cd[pos + 29]]) as usize;
        let extra_len = u16::from_le_bytes([cd[pos + 30], cd[pos + 31]]) as usize;
        let comment_len = u16::from_le_bytes([cd[pos + 32], cd[pos + 33]]) as usize;
        let local_off =
            u32::from_le_bytes([cd[pos + 42], cd[pos + 43], cd[pos + 44], cd[pos + 45]]) as u64;

        let name_start = pos + 46;
        let name_end = name_start.checked_add(name_len)?;
        if name_end > cd.len() {
            break;
        }
        if &cd[name_start..name_end] == needle {
            if method != 0 || uncomp_size > (4 << 20) {
                return None;
            }
            f.seek(SeekFrom::Start(local_off)).ok()?;
            let mut lh = [0u8; 30];
            f.read_exact(&mut lh).ok()?;
            if lh[0..4] != [0x50, 0x4b, 0x03, 0x04] {
                return None;
            }
            let l_name = u16::from_le_bytes([lh[26], lh[27]]) as u64;
            let l_extra = u16::from_le_bytes([lh[28], lh[29]]) as u64;
            let data_off = local_off
                .checked_add(30)?
                .checked_add(l_name)?
                .checked_add(l_extra)?;
            f.seek(SeekFrom::Start(data_off)).ok()?;
            let mut data = vec![0u8; uncomp_size];
            f.read_exact(&mut data).ok()?;
            return Some(data);
        }
        pos = name_end.checked_add(extra_len)?.checked_add(comment_len)?;
    }
    None
}

fn decode_hex(s: &str) -> Result<Vec<u8>, String> {
    let clean: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    if clean.len() % 2 != 0 {
        return Err(format!("odd hex length: {s}"));
    }
    let mut out = Vec::with_capacity(clean.len() / 2);
    let bytes = clean.as_bytes();
    for i in (0..bytes.len()).step_by(2) {
        let part = std::str::from_utf8(&bytes[i..i + 2]).map_err(|e| e.to_string())?;
        let val = u8::from_str_radix(part, 16).map_err(|e| e.to_string())?;
        out.push(val);
    }
    Ok(out)
}

fn parse_u64_str(s: &str) -> Result<u64, String> {
    let trimmed = s.trim();
    if let Some(hex) = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
    {
        u64::from_str_radix(hex, 16).map_err(|e| e.to_string())
    } else {
        trimmed.parse::<u64>().map_err(|e| e.to_string())
    }
}

struct JsonCursor<'a> {
    src: &'a [u8],
    pos: usize,
}

impl<'a> JsonCursor<'a> {
    fn new(s: &'a str) -> Self {
        Self {
            src: s.as_bytes(),
            pos: 0,
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.src.len() && self.src[self.pos].is_ascii_whitespace() {
            self.pos += 1;
        }
    }

    fn peek(&mut self) -> Option<u8> {
        self.skip_ws();
        self.src.get(self.pos).copied()
    }

    fn expect(&mut self, ch: u8) -> Result<(), String> {
        self.skip_ws();
        if self.src.get(self.pos) == Some(&ch) {
            self.pos += 1;
            Ok(())
        } else {
            Err(format!("expected '{}' at offset {}", ch as char, self.pos))
        }
    }

    fn parse_string(&mut self) -> Result<String, String> {
        self.expect(b'"')?;
        let mut out = String::new();
        while self.pos < self.src.len() {
            let b = self.src[self.pos];
            self.pos += 1;
            match b {
                b'"' => return Ok(out),
                b'\\' => {
                    if self.pos >= self.src.len() {
                        return Err("unterminated escape".into());
                    }
                    let esc = self.src[self.pos];
                    self.pos += 1;
                    match esc {
                        b'"' => out.push('"'),
                        b'\\' => out.push('\\'),
                        b'/' => out.push('/'),
                        b'n' => out.push('\n'),
                        b'r' => out.push('\r'),
                        b't' => out.push('\t'),
                        other => out.push(other as char),
                    }
                }
                _ => out.push(b as char),
            }
        }
        Err("unterminated string".into())
    }

    fn parse_number_string(&mut self) -> Result<String, String> {
        self.skip_ws();
        let start = self.pos;
        while self.pos < self.src.len()
            && (self.src[self.pos].is_ascii_digit() || self.src[self.pos] == b'-')
        {
            self.pos += 1;
        }
        if self.pos == start {
            return Err(format!("expected number at offset {}", self.pos));
        }
        Ok(String::from_utf8_lossy(&self.src[start..self.pos]).into_owned())
    }

    fn skip_value(&mut self) -> Result<(), String> {
        match self.peek() {
            Some(b'"') => {
                self.parse_string()?;
                Ok(())
            }
            Some(b'{') => {
                self.pos += 1;
                if self.peek() == Some(b'}') {
                    self.pos += 1;
                    return Ok(());
                }
                loop {
                    self.parse_string()?;
                    self.expect(b':')?;
                    self.skip_value()?;
                    match self.peek() {
                        Some(b',') => self.pos += 1,
                        Some(b'}') => {
                            self.pos += 1;
                            return Ok(());
                        }
                        _ => return Err("expected ',' or '}'".into()),
                    }
                }
            }
            Some(b'[') => {
                self.pos += 1;
                if self.peek() == Some(b']') {
                    self.pos += 1;
                    return Ok(());
                }
                loop {
                    self.skip_value()?;
                    match self.peek() {
                        Some(b',') => self.pos += 1,
                        Some(b']') => {
                            self.pos += 1;
                            return Ok(());
                        }
                        _ => return Err("expected ',' or ']'".into()),
                    }
                }
            }
            Some(_) => {
                while self.pos < self.src.len()
                    && !matches!(self.src[self.pos], b',' | b'}' | b']')
                    && !self.src[self.pos].is_ascii_whitespace()
                {
                    self.pos += 1;
                }
                Ok(())
            }
            None => Err("unexpected EOF".into()),
        }
    }

    fn parse_patch_object(&mut self) -> Result<PatchEntry, String> {
        self.expect(b'{')?;
        let mut tag = None;
        let mut file_offset = None;
        let mut orig = None;
        let mut patched = None;

        if self.peek() != Some(b'}') {
            loop {
                let key = self.parse_string()?;
                self.expect(b':')?;
                match key.as_str() {
                    "tag" => tag = Some(self.parse_string()?),
                    "file_offset" => {
                        let s = if self.peek() == Some(b'"') {
                            self.parse_string()?
                        } else {
                            self.parse_number_string()?
                        };
                        file_offset = Some(parse_u64_str(&s)?);
                    }
                    "orig_hex" => orig = Some(decode_hex(&self.parse_string()?)?),
                    "patched_hex" => patched = Some(decode_hex(&self.parse_string()?)?),
                    _ => self.skip_value()?,
                }
                match self.peek() {
                    Some(b',') => self.pos += 1,
                    Some(b'}') => break,
                    _ => return Err("expected ',' or '}' in patch object".into()),
                }
            }
        }
        self.expect(b'}')?;
        let tag = tag.ok_or("patch entry missing 'tag'")?;
        let file_offset = file_offset.ok_or("patch entry missing 'file_offset'")?;
        let orig = orig.ok_or("patch entry missing 'orig_hex'")?;
        let patched = patched.ok_or("patch entry missing 'patched_hex'")?;
        if orig.len() != patched.len() || patched.is_empty() {
            return Err(format!("invalid patch byte lengths for {tag}"));
        }
        Ok(PatchEntry {
            tag,
            file_offset,
            orig,
            patched,
        })
    }
}

pub fn parse_manifest_json(text: &str) -> Result<PatchManifest, String> {
    let mut cur = JsonCursor::new(text);
    cur.expect(b'{')?;
    let mut build_id = String::new();
    let mut patches = Vec::new();

    if cur.peek() != Some(b'}') {
        loop {
            let key = cur.parse_string()?;
            cur.expect(b':')?;
            match key.as_str() {
                "build_id" => build_id = cur.parse_string()?.to_ascii_lowercase(),
                "patches" => {
                    cur.expect(b'[')?;
                    if cur.peek() != Some(b']') {
                        loop {
                            patches.push(cur.parse_patch_object()?);
                            match cur.peek() {
                                Some(b',') => cur.pos += 1,
                                Some(b']') => break,
                                _ => return Err("expected ',' or ']' in patches array".into()),
                            }
                        }
                    }
                    cur.expect(b']')?;
                }
                _ => cur.skip_value()?,
            }
            match cur.peek() {
                Some(b',') => cur.pos += 1,
                Some(b'}') => break,
                _ => return Err("expected ',' or '}' in manifest root".into()),
            }
        }
    }
    cur.expect(b'}')?;
    Ok(PatchManifest { build_id, patches })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn parses_manifest_json_with_hex_and_decimal_offsets() {
        let json = r#"{
            "schema": 1,
            "build_id": "0000000000000000000000000000000000000000",
            "patches": [
                {
                    "tag": "staff.devtools",
                    "file_offset": "0x1d57784",
                    "orig_hex": "ff0301d1fd7b02a9",
                    "patched_hex": "20008052c0035fd6"
                },
                {
                    "tag": "experiment.release_access.1",
                    "file_offset": 44545036,
                    "orig_hex": "e0179f1a",
                    "patched_hex": "20008052"
                }
            ],
            "runtime_flags": {
                "staff.devtools": {
                    "vaddr": 100164368,
                    "default": 1
                }
            }
        }"#;
        let m = parse_manifest_json(json).expect("valid manifest");
        assert_eq!(m.build_id, "0000000000000000000000000000000000000000");
        assert_eq!(m.patches.len(), 2);
        assert_eq!(m.patches[0].tag, "staff.devtools");
        assert_eq!(m.patches[0].file_offset, 0x1d57784);
        assert_eq!(
            m.patches[0].orig,
            vec![0xff, 0x03, 0x01, 0xd1, 0xfd, 0x7b, 0x02, 0xa9]
        );
        assert_eq!(
            m.patches[0].patched,
            vec![0x20, 0x00, 0x80, 0x52, 0xc0, 0x03, 0x5f, 0xd6]
        );
        assert_eq!(m.patches[1].file_offset, 0x2a7b40c);
    }

    #[test]
    fn reads_stored_manifest_from_zip_file() {
        let dir = std::env::temp_dir();
        let zip_path = dir.join(format!("cinder-manifest-test-{}.apk", std::process::id()));
        let payload = br#"{"schema":1,"build_id":"abcd","patches":[]}"#;
        let name = APK_MANIFEST_ENTRY.as_bytes();

        let mut buf = Vec::new();
        let extra = [0u8; 2];

        buf.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        buf.extend_from_slice(&20u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(&(name.len() as u16).to_le_bytes());
        buf.extend_from_slice(&(extra.len() as u16).to_le_bytes());
        buf.extend_from_slice(name);
        buf.extend_from_slice(&extra);
        buf.extend_from_slice(payload);

        let cd_off = buf.len() as u32;
        buf.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        buf.extend_from_slice(&20u16.to_le_bytes());
        buf.extend_from_slice(&20u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(&(name.len() as u16).to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        buf.extend_from_slice(name);
        let cd_size = (buf.len() as u32) - cd_off;

        buf.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&1u16.to_le_bytes());
        buf.extend_from_slice(&1u16.to_le_bytes());
        buf.extend_from_slice(&cd_size.to_le_bytes());
        buf.extend_from_slice(&cd_off.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());

        {
            let mut f = File::create(&zip_path).unwrap();
            f.write_all(&buf).unwrap();
        }
        let read = read_zip_stored_entry(&zip_path, APK_MANIFEST_ENTRY).expect("found entry");
        let _ = fs::remove_file(&zip_path);
        assert_eq!(read, payload);
        let m = parse_manifest_json(std::str::from_utf8(&read).unwrap()).unwrap();
        assert_eq!(m.build_id, "abcd");
    }
}
