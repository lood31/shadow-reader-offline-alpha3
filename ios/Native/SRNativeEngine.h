#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
/// All work methods run on one physical queue. Only cancelRequest: is cross-thread.
@interface SRNativeEngine : NSObject
- (void)beginRequest:(NSString *)requestId NS_SWIFT_NAME(beginRequest(_:));
- (void)endRequest:(NSString *)requestId NS_SWIFT_NAME(endRequest(_:));
- (void)cancelRequest:(NSString *)requestId NS_SWIFT_NAME(cancelRequest(_:));
- (BOOL)loadPronunciationAt:(NSString *)path error:(NSError **)error NS_SWIFT_NAME(loadPronunciation(at:));
- (nullable NSString *)phonemize:(NSString *)text error:(NSError **)error NS_SWIFT_NAME(phonemize(_:));
- (nullable NSArray<NSNumber *> *)speechRange:(NSData *)samples error:(NSError **)error NS_SWIFT_NAME(speechRange(_:));
- (nullable NSArray<NSArray<NSNumber *> *> *)logits:(NSData *)samples error:(NSError **)error NS_SWIFT_NAME(logits(_:));
- (nullable NSString *)transcribe:(NSData *)samples model:(NSString *)model error:(NSError **)error NS_SWIFT_NAME(transcribe(_:model:));
- (void)releasePronunciation;
+ (uint64_t)physicalFootprint;
@end
NS_ASSUME_NONNULL_END
