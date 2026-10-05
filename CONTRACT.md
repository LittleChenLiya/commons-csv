<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# CSV 序列校验器契约（CSV Sequence Validator Contract）

`CSVSequenceValidator<K, S>` 按业务键维护有限状态。调用方声明允许的状态转换。每条记录只有在**字段转换**与**转换合法性**都通过时才更新状态。

## 配置

- `initialState(S)`：键在首次合法记录之前的初始状态。
- `transitions(Map<S, Set<S>>)`：允许的状态转换（旧状态 -> 允许的后继集合）。
- `maxKeys(int)`：不同键的数量上限。
- `keyParser(FieldParser<K>)`：键字段转换器，抛出异常表示字段转换失败。
- `stateParser(FieldParser<S>)`：目标状态字段转换器，抛出异常表示字段转换失败。

## 校验语义

1. 解析键与目标状态。任一失败 -> 非法行（`FIELD_CONVERSION`），该键状态不变。
2. 新键（旧状态为初始状态）：
   - 若目标等于初始状态 -> 建立该键。
   - 否则检查初始状态 -> 目标是否在转换表中；不在 -> 非法行（`ILLEGAL_TRANSITION`），状态不变。
3. 已有键（旧状态为当前状态）：检查当前状态 -> 目标是否在转换表中；不在 -> 非法行（`ILLEGAL_TRANSITION`），状态不变。
4. 新键会使键数超过 `maxKeys` -> 非法行（`CAPACITY`）；不淘汰任何仍活跃的状态，已有状态不变。

## 非法行

非法行携带：

- `recordNumber`：记录号（1 基）。
- `oldState`：该行之前的旧状态（键无法解析时为 `null`）。
- `expectedStates`：期望集合（旧状态的允许后继；旧状态未知时为空集）。
- `reason`：`FIELD_CONVERSION` / `ILLEGAL_TRANSITION` / `CAPACITY`。

非法行绝不推进状态，后续记录针对不变的状态继续校验。

## 只读

校验器只读取记录，不写业务数据。

## 验收示例

订单状态：`new -> paid -> sent`。

- 订单 a 依次 `new`、`paid`、`sent` 通过。
- 订单 a 尝试 `new -> sent` 被拒绝，状态仍为 `new`；随后的 `new -> paid` 仍通过。
- 不同订单各自计状态。
- 容量上限为 1 时，第二个订单被拒绝（`CAPACITY`），第一个订单状态不变且仍可推进。
