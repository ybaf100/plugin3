# Group Survival System

Paper 26.3용 익명 그룹 서바이벌 플러그인.

## 기능
- `/nv` 영구 야간투시 (우유/토템으로 사라지지 않음)
- 겉날개 금지: 금지 중에는 겉날개 액자를 어떤 방법으로도 떼거나 부술 수 없고, 겉날개가 있어도 활공 불가
  - 콘솔에서 수동 전환: `gss elytra on | off | status`
- 오버월드 좌표 숨김 (F3 + Lunar 좌표 모드 차단), 네더/엔드는 좌표 표시
- 오버월드 나침반은 항상 0,0 을 가리킴 (네더/엔드는 바닐라처럼 회전)
- 일반 나침반 들고 F = 현재 좌표를 채팅으로 확인 (나침반 1개 소모)
- 양털(모든 색) 들고 F = 같은 개수의 하얀 현수막으로 변환
- 엔더드래곤 체력 5배
- 데스 메시지는 `&c&l사람이 죽었다.` 로 통일
- 채팅은 근처(기본 32블록) 플레이어에게만 `[지역] <이름> 내용` 형태로 보임
- 명령어는 `/nv` 만 사용 가능
- 표지판은 오버월드 0,0 청크와 주변 8청크에서만 사용 가능, 네더/엔드는 불가 (안내 문구로 바뀜)
- 오버월드 0,0 청크와 주변 8청크는 무적지역 (블록 설치/파괴, 몹 스폰은 허용) + 부술 수 없는 고정 엔더상자
- 접속자 비공개: 입장/퇴장/발전과제 메시지 제거, 서버 목록 인원 숨김, 위치 표시줄 끔,
  가까이 있는 플레이어만 전송(이름은 `익명`, 이름표 숨김, 스킨은 보임), "n명 자는 중" 메시지 차단
- 바닐라 + Lunar Client만 접속 허용
- Lunar Client 모드 제한 (미니맵, 좌표, 웨이포인트, 위치 표시줄, 팀뷰, 마커, MumbleLink 등)

## 예정
- 첫 접속 무작위 스폰 → 영구 개인 스폰: 월드보더 시스템과 함께 구현

## 설치
`plugins/` 폴더에 넣을 것:
1. `GroupSurvivalSystem-x.y.z.jar` (GitHub Actions 빌드 결과물)
2. [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 이상 (필수)
3. [Apollo-Bukkit](https://modrinth.com/plugin/lunar-client-apollo) 1.3.0 이상 (Lunar 감지와 모드 제한에 필요)

권장 `server.properties`: `hide-online-players=true`, `enable-query=false`, `view-distance=18`, `simulation-distance=18` (서버가 버거우면 줄이기), `spawn-protection=0`

## 빌드
GitHub Actions가 푸시할 때마다 빌드하고 jar를 아티팩트로 올립니다.
로컬 빌드: JDK 25 필요, `./gradlew build`

## 참고
- 클라이언트 판별은 클라이언트가 보고하는 값이라 위조된 클라이언트는 막을 수 없습니다.
