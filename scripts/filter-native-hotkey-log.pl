use strict;
use warnings;
use JSON::PP;
use POSIX qw(strftime);

$| = 1;
my @definitions = (
    ['Observed native Fn edge', 'native_fn_edge', ['pressed']],
    ['Native Fn dispatch state', 'native_fn_state', ['pressed', 'was_pressed', 'awaiting_release', 'held_combo_count', 'pending_base_press', 'combo_used']],
    ['Native Fn dispatch outcome', 'native_fn_outcome', ['pressed', 'business_accepted']],
    ['Native release barrier reconciled', 'native_release_reconciled', []],
    ['Dispatching configured shortcut', 'shortcut_dispatch', ['role']],
    ['Shortcut blocked by registration state', 'shortcut_registration_blocked', ['role']],
    ['Native shortcut dispatch paused', 'shortcut_paused', ['role']],
);
my $encoder = JSON::PP->new->canonical;

sub emit_line {
    my ($line) = @_;
    $line =~ s/\e\[[0-9;]*m//g;
    my @matches = grep { index($line, $_->[0]) >= 0 } @definitions;
    return unless @matches == 1;
    my $definition = $matches[0];
    my %output = (
        observedAt => strftime('%Y-%m-%dT%H:%M:%SZ', gmtime),
        event => $definition->[1],
    );
    for my $field (@{$definition->[2]}) {
        my @values = $line =~ /\b\Q$field\E=([^\s]+)/g;
        return unless @values == 1;
        my $value = $values[0];
        if ($field eq 'role') {
            $value =~ s/^"(.*)"$/$1/;
            return unless $value =~ /^(dictation|ask|translate_selection)$/;
            $output{$field} = $value;
        } elsif ($field eq 'held_combo_count') {
            return unless $value =~ /^\d{1,3}$/ && $value <= 128;
            $output{$field} = 0 + $value;
        } else {
            return unless $value eq 'true' || $value eq 'false';
            $output{$field} = $value eq 'true' ? JSON::PP::true : JSON::PP::false;
        }
    }
    print $encoder->encode(\%output), "\n";
}

my $buffer = '';
my $dropping = 0;
my $chunk;
while (sysread(STDIN, $chunk, 1024)) {
    for my $character (split //, $chunk) {
        if ($character eq "\n") {
            emit_line($buffer) unless $dropping;
            $buffer = '';
            $dropping = 0;
        } elsif (!$dropping) {
            $buffer .= $character;
            if (length($buffer) > 4096) {
                $buffer = '';
                $dropping = 1;
            }
        }
    }
}
emit_line($buffer) if !$dropping && length($buffer);
