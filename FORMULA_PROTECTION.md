# CSV 公式注入保护策略（Formula Injection Protection）

## 背景

电子表格软件（Excel、LibreOffice Calc、Google Sheets 等）会把单元格中**忽略前导空白后**以
`=`、`+`、`-`、`@` 开头的文本解释为公式。导出 CSV 时若不加防护，诸如 `=1+1`、
`@SUM(...)`、`-2+3` 之类的文本在被打开时可能被执行（CSV/公式注入）。

## 策略要点（显式开启，旧行为不变）

本仓库新增专项类 `org.apache.commons.csv.FormulaProtectionConverter`（字段转换器），
与 `CSVPrinter` 配合使用。**默认不启用**：只有调用方显式把字段交给该转换器时才生效，
`CSVPrinter` 原有打印行为完全不变。

1. **只检查文本字段**：忽略前导空白（`Character.isWhitespace`，含空格、tab、换行）后，
   首字符为 `=`、`+`、`-`、`@` 的文本被视为危险。
2. **危险文本加单引号前缀**：在原始值前拼接 `'`（前导空白保留在原位置），
   电子表格把前导单引号当作"字面文本"标记，不再求值。
3. **显式数字走严格语法**：调用方通过 `convertNumber` / `printNumber` 显式声明为数字的值，
   必须完整匹配严格十进制数字语法 `[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?`。
   合法负数（如 `-12.5`）是真实数字，原样保留、**不会**被误加前缀；
   声明为数字却传入 `1+2`、空串、含空白/千分位/`NaN` 等一律抛出
   `IllegalArgumentException`——数字声明不会被盲目信任。
4. **不把 CSV 双引号当防公式措施**：双引号只是 CSV 转义手段，电子表格解析后仍会求值，
   因此防护只靠前缀 `'`。转换产生的是新字符串，**调用方的原字段值不被修改**；
   转换后的值仍交给 `CSVPrinter.print`，由打印器上配置的 `CSVFormat` 照常做 CSV 转义。

## 边界行为

- 空串、纯空白串、`null`：原样通过，不加前缀。
- 原值已以单引号开头（如 `'=1`）：首字符是 `'`，非危险字符，不再重复加前缀。
- Unicode：只识别 ASCII 的 `= + - @`；全角 `＝`（U+FF1D）等不触发，中文/emoji 文本原样输出；
  危险 ASCII 前缀后的 Unicode 内容（如 `=héllo中文`）照常保护。
- `Number` 实例：`toString()` 匹配严格数字语法则原样输出；`NaN`、`Infinity` 退化为文本保护
  （`-Infinity` 会被加前缀）。

## 用法示例

```java
FormulaProtectionConverter converter = new FormulaProtectionConverter();
StringWriter sw = new StringWriter();
try (CSVPrinter printer = CSVFormat.DEFAULT.print(sw)) {
    converter.printRecord(printer, "name", "formula", "balance");
    converter.print(printer, "alice");
    converter.print(printer, "=1+1");        // 危险文本 -> 加 ' 前缀
    converter.printNumber(printer, "-12.5"); // 显式数字，严格语法 -> 原样保留
    printer.println();
    converter.print(printer, "bob");
    converter.print(printer, "\t@cmd");      // tab 后接 @ -> 加 ' 前缀
    converter.printNumber(printer, "1.5E3"); // 科学计数法 -> 原样保留
    printer.println();
}
```

完整可运行示例见 `src/test/java/org/apache/commons/csv/FormulaProtectionExample.java`。

## 真实运行输出

以上示例（`FormulaProtectionExample`）在本仓库当前代码上的真实输出
（`^I` 表示 tab，行尾为 CRLF）：

```
--- raw CSV output ---
name,formula,balance
alice,'=1+1,-12.5
bob,'^I@cmd,1.5E3
--- values parsed back with CSVParser ---
[name, formula, balance]
[alice, '=1+1, -12.5]
[bob, '^I@cmd, 1.5E3]
--- rejected numeric declaration ---
convertNumber("1+2") threw: Not a strict decimal number: 1+2
```

可以看到：文本 `=1+1` 被保护为 `'=1+1`；显式数字 `-12.5` 原样保留；
tab 后接 `@` 的文本被保护为 `'<tab>@cmd`；用原 `CSVParser` 读回后前缀仍然存在；
声明为数字的 `1+2` 被拒绝。

## 测试

专项测试：`src/test/java/org/apache/commons/csv/FormulaProtectionConverterTest.java`
（14 个用例，覆盖危险文本、前导空白/tab、严格数字语法、拒绝伪数字、空串、单引号原值、
Unicode、打印-解析回环确认前缀、CSV 转义保持、旧打印行为不变）。
