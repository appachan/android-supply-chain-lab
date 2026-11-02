import base64, re, sys, pathlib
# usage: flipsha <xml> <artifact-name> | breakasc <file.asc> | fixture <xml> | removebc <xml>
def crc24(data):
    c = 0xB704CE
    for b in data:
        c ^= b << 16
        for _ in range(8):
            c <<= 1
            if c & 0x1000000: c ^= 0x1864CFB
    return c & 0xFFFFFF
command, path = sys.argv[1:3]
p = pathlib.Path(path)
if command == "flipsha":
    t = p.read_text()
    m = re.search(r'<artifact name="%s">\s*<sha256 value="([0-9a-f]+)"' % re.escape(sys.argv[3]), t)
    v = m.group(1); nv = ("0" if v[0] != "0" else "1") + v[1:]
    p.write_text(t[:m.start(1)] + nv + t[m.end(1):]); print(v, "->", nv)
elif command == "breakasc":
    lines = p.read_text().splitlines()
    i = next(n for n, l in enumerate(lines) if l == "") + 1
    j = next(n for n, l in enumerate(lines) if l.startswith("="))
    body = bytearray(base64.b64decode("".join(lines[i:j])))
    body[-5] ^= 0xFF  # 署名の値の末尾の 1 byte
    b = base64.b64encode(bytes(body)).decode()
    new = lines[:i] + [b[k:k+64] for k in range(0, len(b), 64)] + ["=" + base64.b64encode(crc24(body).to_bytes(3, "big")).decode()] + lines[j+1:]
    p.write_text("\n".join(new) + "\n")
elif command == "fixture":
    # ライブラリの trusted-key と component を消す。検証用の鍵の起点を作る
    t = p.read_text()
    t, n1 = re.subn(r'\s*<trusted-key id="8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6" group="com.github.appachan"[^\n]*', '', t)
    t, n2 = re.subn(r'      <component group="com.github.appachan" name="android-supply-chain-lab-lib" version="0.3.0">.*?</component>\n', '', t, flags=re.S)
    p.write_text(t); print("fixture", n1, n2)
elif command == "removebc":
    t = p.read_text()
    t, n = re.subn(r'      <component group="org.bouncycastle" name="bc-jdk18on-bom" version="1.80.2">.*?</component>\n', '', t, flags=re.S)
    p.write_text(t); print("removed", n)
