import useMeQuery from "@api/queries/useMeQuery";
import Avatar from "@primitives/ui/Avatar";

/**
 * 로그인한 회원의 프로필 아바타.
 *
 * 사진이 없는 회원은 `Avatar`가 닉네임 첫 글자로 대신 그려요. 이것이 기본 프로필 이미지예요(AUTH-R15).
 */
export default function MemberProfileAvatar() {
  const { data: me } = useMeQuery();

  return (
    <Avatar
      label="내 프로필"
      src={me?.profileImageUrl ?? undefined}
      name={me?.nickname}
      size={32}
    />
  );
}
