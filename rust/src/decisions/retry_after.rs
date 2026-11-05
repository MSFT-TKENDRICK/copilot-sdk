use std::time::{SystemTime, UNIX_EPOCH};

use super::MAX_SAFE_INTEGER;

pub(super) fn now_epoch_ms() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
        Err(error) => -i64::try_from(error.duration().as_millis()).unwrap_or(i64::MAX),
    }
}

pub(super) fn parse(
    milliseconds: Option<&str>,
    seconds: Option<&str>,
    now_epoch_ms: i64,
) -> Option<u64> {
    milliseconds
        .and_then(|value| decimal_delay(value, 0))
        .or_else(|| {
            let value = super::trim_http_whitespace(seconds?);
            decimal_delay(value, 3).or_else(|| {
                let then = imf_fixdate(value)?;
                let delay = (i128::from(then) - i128::from(now_epoch_ms)).max(0);
                (delay <= i128::from(MAX_SAFE_INTEGER)).then_some(delay as u64)
            })
        })
}

/// Decimal arithmetic preserves the exact upper bound and rounds UP, without
/// accepting exponent/sign syntax or rounding a large integer through f64.
fn decimal_delay(value: &str, decimal_places: u32) -> Option<u64> {
    let value = super::trim_http_whitespace(value);
    let (whole, fraction) = match value.split_once('.') {
        Some((whole, fraction)) if !fraction.is_empty() => (whole, fraction),
        Some(_) => return None,
        None => (value, ""),
    };
    if whole.is_empty()
        || !whole.bytes().all(|b| b.is_ascii_digit())
        || !fraction.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let mut result = whole
        .parse::<u64>()
        .ok()?
        .checked_mul(10u64.pow(decimal_places))?;
    for (i, digit) in fraction.bytes().take(decimal_places as usize).enumerate() {
        result = result
            .checked_add(u64::from(digit - b'0') * 10u64.pow(decimal_places - 1 - i as u32))?;
    }
    if fraction
        .bytes()
        .skip(decimal_places as usize)
        .any(|b| b != b'0')
    {
        result = result.checked_add(1)?;
    }
    (result <= MAX_SAFE_INTEGER as u64).then_some(result)
}

fn imf_fixdate(value: &str) -> Option<i64> {
    let bytes = value.as_bytes();
    if bytes.len() != 29
        || !bytes.is_ascii()
        || &bytes[3..5] != b", "
        || bytes[7] != b' '
        || bytes[11] != b' '
        || bytes[16] != b' '
        || bytes[19] != b':'
        || bytes[22] != b':'
        || &bytes[25..] != b" GMT"
    {
        return None;
    }
    let weekday = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        .iter()
        .position(|day| *day == &value[..3])? as i64;
    let month = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ]
    .iter()
    .position(|month| *month == &value[8..11])? as i64
        + 1;
    let number = |start, end| {
        let text = &value[start..end];
        text.bytes()
            .all(|b| b.is_ascii_digit())
            .then(|| text.parse::<i64>().ok())
            .flatten()
    };
    let day = number(5, 7)?;
    let year = number(12, 16)?;
    let hour = number(17, 19)?;
    let minute = number(20, 22)?;
    let second = number(23, 25)?;
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days_in_month = match month {
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => 31,
    };
    if year == 0 || !(1..=days_in_month).contains(&day) || hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    // Proleptic Gregorian days since 1970-01-01, using 400-year eras.
    let year = year - i64::from(month <= 2);
    let era = year.div_euclid(400);
    let year_of_era = year - era * 400;
    let month = month + if month > 2 { -3 } else { 9 };
    let day_of_year = (153 * month + 2) / 5 + day - 1;
    let days = era * 146_097 + year_of_era * 365 + year_of_era / 4 - year_of_era / 100
        + day_of_year
        - 719_468;
    if (days + 4).rem_euclid(7) != weekday {
        return None;
    }
    Some((days * 86_400 + hour * 3_600 + minute * 60 + second) * 1_000)
}
