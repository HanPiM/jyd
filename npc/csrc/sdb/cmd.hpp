#pragma once
#include <functional>
#include <string_view>
#include <ranges>
#include <vector>
#include <charconv>
#include <tuple>
#include <concepts>
#include <system_error>
#include <cstdint>

namespace clscmd {
	using std::string_view;
	using std::string;
	using std::function;

	using toks_t = std::vector<string_view>;

namespace _impl {
template <typename T>
concept CanFromChars = std::integral<T> || std::floating_point<T>;
template <typename T>
concept CanFromStrView = requires(std::string_view sv) {T{sv};};

auto parse(string_view s,CanFromChars auto& v,int base=10){
	return std::from_chars(s.begin(),s.end(),v,base).ec;
}
inline auto parse(string_view s,CanFromStrView auto& v){
	v=s;
	return std::errc();
}

}

inline auto make_rawtoks(string_view str){
	using namespace std::views;
	return str | split(' ')
		| transform([](auto&& rng) {
				return string_view(rng.begin(), rng.size());
		});
}
inline auto make_toks(string_view str){
	auto v=make_rawtoks(str);
	return toks_t(v.begin(),v.end());
}

struct expr_t{
	string_view raw;
	expr_t(){}
	expr_t(string_view s):raw(s){}
	uint64_t eval()const;
};

using invoke_error = std::errc;
constexpr invoke_error invoke_success=invoke_error();

struct command_t{
	string_view description;
	function<invoke_error(toks_t)> invoke;

	command_t(){}

	// TODO: use C++23 bind_back
	
	template <typename Class, typename Ret, typename... Args,typename... Defaults>
	command_t(
		string_view desc,
		Class* obj, Ret(Class::*func)(Args...),
		Defaults&&... defaults
	):description(desc){
		using namespace std;
		auto def_args=make_tuple(forward<Defaults>(defaults)...);
		invoke=[obj, func, def_args](toks_t toks) {
      tuple<decay_t<Args>...> args;
			constexpr size_t N_args = sizeof...(Args);
      constexpr size_t N_def  = sizeof...(Defaults);
      constexpr size_t I_required_end = N_args - N_def;

			const size_t n_tok = toks.size();

      if(n_tok > N_args) return errc::argument_list_too_long;
			if(n_tok+N_def<N_args) return errc::invalid_argument;

			bool ok=true;
			errc ec;

      [&]<size_t... Is>(index_sequence<Is...>) { (([&]{
				if (Is < n_tok) {
					if(!ok)return;
					ec = _impl::parse(toks[Is], get<Is>(args));
					ok=(ec==errc());
					if(!ok)printf("parse failed arg#%zu(%s) as typeid`%s\n",
							Is, toks[Is].data(), typeid(decltype(get<Is>(args))).name()
							);
				}
				else{
					if constexpr (Is >= I_required_end) {
						get<Is>(args) = get<Is-I_required_end>(def_args);
					} else { // should return early when check n_tok+n_def
						throw std::logic_error("unreachable");
					}
				}
			}()),...); }(index_sequence_for<Args...>{});

			if(!ok)return invoke_error(ec);

			apply(bind_front(func, obj), args);
			return invoke_success;
    };
	}

};
using command_table=std::unordered_map<string_view, command_t>;
invoke_error exec(const command_table& table,string_view cmdline);

}
