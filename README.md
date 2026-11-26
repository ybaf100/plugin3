# Group Survival System

Paper 26.3용 익명 그룹 서바이벌 플러그인.

## 기능
- `/nv` 영구 야간투시 (우유/토템으로 사라지지 않음)
- 겉날개 금지: 금지 중에는 겉날개 액자를 어떤 방법으로도 떼거나 부술 수 없고, 겉날개가 있어도 활공 불가
  - 콘솔: `gss elytra on | off | status`
- 오버월드 좌표 숨김 (F3 reducedDebugInfo + Lunar 좌표 모드 차단), 네더/엔드는 좌표 표시
- 엔더드래곤 체력 5배
- 통신 금지: 채팅, 명령어(`/nv` 외), 책, 모루 이름 변경, 이름표
- 표지판은 0,0 청크와 주변 8청크에서만 사용 가능 (그 외에서는 안내 문구로 바뀜)
- 접속자 비공개: 입장/퇴장/사망/발전과제 메시지 제거, 서버 목록 인원 숨김, 위치 표시줄 끔,
  가까이 있는 플레이어만 전송(이름은 `익명`, 이름표 숨김, 스킨은 보임), "n명 자는 중" 메시지 차단
- 바닐라 + Lunar Client만 접속 허용
- Lunar Client 모드 제한 (미니맵, 좌표, 웨이포인트, 위치 표시줄, 팀뷰, 마커, MumbleLink 등)

## 설치
`plugins/` 폴더에 넣을 것:
1. `GroupSurvivalSystem-x.y.z.jar` (GitHub Actions 빌드 결과물)
2. [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 이상 (필수)
3. [Apollo-Bukkit](https://modrinth.com/plugin/lunar-client-apollo) 1.3.0 이상 (Lunar 감지와 모드 제한에 필요)

권장 `server.properties`: `hide-online-players=true`, `enable-query=false`

## 빌드
GitHub Actions가 푸시할 때마다 빌드하고 jar를 아티팩트로 올립니다.
로컬 빌드: JDK 25 필요, `./gradlew build`

## 참고
- 좌표 오프셋(`coordinates.offset.enabled`)은 실험 기능이며 기본값은 꺼져 있습니다. 이동 패킷 복원에 알려진 문제가 있습니다.
- 클라이언트 판별은 클라이언트가 보고하는 값이라 위조된 클라이언트는 막을 수 없습니다.
